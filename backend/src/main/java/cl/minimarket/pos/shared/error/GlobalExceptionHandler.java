package cl.minimarket.pos.shared.error;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Traduce toda excepción de la API a un {@link ProblemDetail} RFC 9457 (A.5, F.1).
 *
 * <p>Formato de salida ({@code application/problem+json}):
 * <pre>
 * { "type": "https://pos-minimarket/errors/validation-error", "title": "...", "status": 400,
 *   "detail": "...", "instance": "/api/v1/...", "code": "VALIDATION_ERROR",
 *   "traceId": "...", "errors": [ { "field": "...", "code": "...", "message": "..." } ] }
 * </pre>
 * {@code code} es un valor estable de {@link ErrorCode}; {@code traceId} se toma del MDC
 * (clave {@value #TRACE_ID_KEY}) y se omite si no hay uno; {@code errors[]} solo aparece en
 * errores de validación; {@code instance} lo completa Spring con la ruta de la solicitud.
 *
 * <p>Decisión de diseño: extiende {@link ResponseEntityExceptionHandler} para cubrir de una vez
 * las excepciones estándar de Spring MVC (JSON ilegible, tipos de parámetro, 404, 405, etc.).
 * Todas pasan por {@link #handleExceptionInternal}, que normaliza el cuerpo (type, title, code,
 * traceId, errors); así una excepción nueva de Spring queda cubierta sin tocar esta clase.
 *
 * <p>Mapa de excepciones: {@link BusinessException} → su {@link ErrorCode};
 * validación ({@code @Valid}, {@link ConstraintViolationException}, validación de métodos, JSON
 * ilegible, tipo de parámetro) → 400 {@code VALIDATION_ERROR}; sin handler / recurso → 404
 * {@code NOT_FOUND}; {@link AccessDeniedException} → 403 {@code ACCESS_DENIED};
 * {@link AuthenticationException} → 401 {@code UNAUTHENTICATED}; bloqueo optimista → 409
 * {@code CONCURRENT_MODIFICATION}; cualquier otra → 500 sin mensaje interno ni stack trace (queda
 * solo en el log, junto con el {@code traceId}).
 *
 * <p>Estados de protocolo y técnicos: 405 {@code METHOD_NOT_ALLOWED}, 406 {@code NOT_ACCEPTABLE},
 * 415 {@code UNSUPPORTED_MEDIA_TYPE} y 500 {@code INTERNAL_ERROR} están en el catálogo F.1; cualquier
 * otro 4xx de protocolo usa {@code VALIDATION_ERROR} con su estado real (ver {@link #identityFor(int)}).
 * {@link DataIntegrityViolationException}: SQLState 23505 → 409 {@code DUPLICATED_VALUE}; 23514 → 400
 * {@code VALIDATION_ERROR}; 23503 → 409 {@code ENTITY_IN_USE}; otros → 500. Nunca se exponen nombres
 * de constraint ni SQL (solo se registran en el log).
 *
 * <p>Punto de extensión: para mapear una excepción nueva de otro módulo, agregar un
 * {@code @ExceptionHandler} que use {@link #problem(ErrorCode, String)}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Clave del MDC con el identificador de traza (A.5 "Logging"). */
    public static final String TRACE_ID_KEY = "traceId";

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------------------------
    // Excepciones propias y de otras librerías
    // ------------------------------------------------------------------------------------

    /** Error de negocio: estado, título y type vienen del {@link ErrorCode}; agrega extensiones y errors[]. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        LOG.info("Error de negocio {}: {}", ex.getCode(), ex.getMessage());
        ProblemDetail problem = problem(ex.getCode(), ex.getMessage());
        ex.getExtensions().forEach(problem::setProperty);
        if (!ex.getErrors().isEmpty()) {
            problem.setProperty("errors", ex.getErrors());
        }
        return respond(problem);
    }

    /** Violaciones de {@code jakarta.validation} fuera del binding (p. ej. {@code @Validated} en servicios). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_ERROR, "La solicitud contiene datos inválidos.");
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            errors.add(new FieldErrorDetail(leafName(violation.getPropertyPath()),
                    toUpperSnake(constraint), violation.getMessage()));
        }
        problem.setProperty("errors", errors);
        return respond(problem);
    }

    /**
     * Violación de integridad de la BD, traducida por SQLState: 23505 (único) a 409
     * {@code DUPLICATED_VALUE}; 23514 (check) a 400 {@code VALIDATION_ERROR}; 23503 (FK) a 409
     * {@code ENTITY_IN_USE}. Otros casos se tratan como error inesperado. El detalle de la respuesta
     * es genérico: el nombre del constraint solo va al log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        String sqlState = sqlStateOf(ex);
        ErrorCode code = switch (sqlState == null ? "" : sqlState) {
            case "23505" -> ErrorCode.DUPLICATED_VALUE;
            case "23514" -> ErrorCode.VALIDATION_ERROR;
            case "23503" -> ErrorCode.ENTITY_IN_USE;
            default -> null;
        };
        if (code == null) {
            return handleUnexpected(ex);
        }
        LOG.warn("Violación de integridad SQLState={} constraint={}", sqlState, constraintNameOf(ex));
        String detail = switch (code) {
            case DUPLICATED_VALUE -> "Ya existe un registro con el mismo valor.";
            case ENTITY_IN_USE -> "El registro está relacionado con otros datos y no puede modificarse así.";
            default -> "Los datos enviados no cumplen una regla de validación.";
        };
        return respond(problem(code, detail));
    }

    private static String sqlStateOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static String constraintNameOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException hibernate) {
                return hibernate.getConstraintName();
            }
        }
        return null;
    }

    /** Último nodo con nombre de propiedad o parámetro; evita exponer rutas internas como {@code create.arg0.name}. */
    private static String leafName(Path path) {
        String leaf = "";
        for (Path.Node node : path) {
            if ((node.getKind() == ElementKind.PROPERTY || node.getKind() == ElementKind.PARAMETER)
                    && node.getName() != null) {
                leaf = node.getName();
            }
        }
        return leaf;
    }

    /** Falta de permiso (método o URL protegidos): 403 {@code ACCESS_DENIED}. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(problem(ErrorCode.ACCESS_DENIED, "No tiene permiso para realizar esta acción."));
    }

    /** Autenticación fallida o ausente lanzada dentro de un controlador: 401 {@code UNAUTHENTICATED}. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(problem(ErrorCode.UNAUTHENTICATED, "Debe iniciar sesión para continuar."));
    }

    /** Bloqueo optimista (Spring o JPA): el recurso cambió desde que se leyó. */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ProblemDetail> handleOptimisticLock(Exception ex) {
        LOG.info("Modificación concurrente: {}", ex.getClass().getSimpleName());
        return respond(problem(ErrorCode.CONCURRENT_MODIFICATION,
                "El recurso fue modificado por otro usuario. Recargue e intente nuevamente."));
    }

    /**
     * Último recurso: 500 sin stack trace ni mensaje interno en la respuesta. El detalle técnico
     * se registra en el log con el {@code traceId}, que el cliente puede citar al soporte.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        LOG.error("Error no controlado (traceId={})", MDC.get(TRACE_ID_KEY), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocurrió un error inesperado. Si persiste, informe el identificador de traza.");
        apply(problem, ErrorCode.INTERNAL_ERROR);
        return respond(problem);
    }

    // ------------------------------------------------------------------------------------
    // Excepciones estándar de Spring MVC (ResponseEntityExceptionHandler)
    // ------------------------------------------------------------------------------------

    /**
     * Punto único por el que pasan las excepciones estándar de Spring MVC: toma el
     * {@link ProblemDetail} que construye la clase base y lo normaliza (type, title, code,
     * traceId, detalle en español y errors[]).
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response.getBody() instanceof ProblemDetail problem) {
            apply(problem, identityFor(problem.getStatus()));
            describe(ex, problem);
        }
        return response;
    }

    /**
     * Subida que excede {@code spring.servlet.multipart.max-file-size} / {@code max-request-size}.
     * Spring la respondería como 413 genérico; se reemplaza por {@code FILE_INVALID} del catálogo F.1
     * para que el cliente distinga el problema del archivo. El detalle no expone límites ni rutas
     * internas. Otros fallos multipart no relacionados con el tamaño siguen el flujo estándar.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers,
                                                                          HttpStatusCode status,
                                                                          WebRequest request) {
        LOG.info("Subida rechazada por tamaño excedido");
        ProblemDetail problem = problem(ErrorCode.FILE_INVALID,
                "El archivo supera el tamaño máximo permitido.");
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    /** Localiza el detalle y agrega errors[] según el tipo de excepción de Spring MVC. */
    private void describe(Exception ex, ProblemDetail problem) {
        switch (ex) {
            case MethodArgumentNotValidException e -> {
                problem.setDetail("La solicitud contiene datos inválidos.");
                problem.setProperty("errors", bindingErrors(e));
            }
            case HandlerMethodValidationException e -> {
                problem.setDetail("La solicitud contiene datos inválidos.");
                problem.setProperty("errors", methodValidationErrors(e));
            }
            case HttpMessageNotReadableException e ->
                    problem.setDetail("El cuerpo de la solicitud no se puede leer o tiene un formato inválido.");
            case MethodArgumentTypeMismatchException e -> typeMismatch(problem, e.getName());
            case TypeMismatchException e -> typeMismatch(problem, e.getPropertyName());
            case MissingServletRequestParameterException e -> {
                problem.setDetail("Falta un parámetro obligatorio.");
                problem.setProperty("errors", List.of(new FieldErrorDetail(e.getParameterName(), "REQUIRED",
                        "El parámetro es obligatorio.")));
            }
            case NoResourceFoundException e -> problem.setDetail("El recurso solicitado no existe.");
            case NoHandlerFoundException e -> problem.setDetail("El recurso solicitado no existe.");
            case HttpRequestMethodNotSupportedException e ->
                    problem.setDetail("El método HTTP no está permitido para este recurso.");
            default -> {
                // Se conserva el detalle estándar de Spring (no contiene datos internos).
            }
        }
    }

    private static void typeMismatch(ProblemDetail problem, String parameter) {
        problem.setDetail("El valor de un parámetro no es válido.");
        problem.setProperty("errors", List.of(new FieldErrorDetail(
                parameter == null ? "" : parameter, "TYPE_MISMATCH", "El valor no tiene el tipo esperado.")));
    }

    private static List<FieldErrorDetail> bindingErrors(MethodArgumentNotValidException ex) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            errors.add(new FieldErrorDetail(field, toUpperSnake(String.valueOf(error.getCode())),
                    error.getDefaultMessage()));
        }
        return errors;
    }

    private static List<FieldErrorDetail> methodValidationErrors(HandlerMethodValidationException ex) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError ? fieldError.getField()
                        : (parameter == null ? "" : parameter);
                String[] codes = error.getCodes();
                String code = codes == null || codes.length == 0 ? "INVALID" : codes[codes.length - 1];
                errors.add(new FieldErrorDetail(field, toUpperSnake(code), error.getDefaultMessage()));
            }
        }
        return errors;
    }

    // ------------------------------------------------------------------------------------
    // Construcción del problema
    // ------------------------------------------------------------------------------------

    /**
     * Crea el {@link ProblemDetail} de un código del catálogo, con estado, title, type, code y
     * traceId. Es el punto de entrada para los handlers que se agreguen en otros módulos.
     *
     * @param code código del catálogo F.1
     * @param detail explicación segura para el usuario
     * @return problema listo para completar con extensiones
     */
    protected ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        apply(problem, code);
        return problem;
    }

    private static void apply(ProblemDetail problem, ErrorCode code) {
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId != null) {
            problem.setProperty(TRACE_ID_KEY, traceId);
        }
    }

    /** Código del catálogo correspondiente a un estado HTTP generado por Spring MVC. */
    private static ErrorCode identityFor(int status) {
        return switch (status) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_ERROR;
        };
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** {@code NotBlank} pasa a {@code NOT_BLANK}. */
    private static String toUpperSnake(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
