package cl.minimarket.pos.shared.error;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Excepción de negocio: se lanza desde los casos de uso y {@link GlobalExceptionHandler} la
 * traduce a un {@code ProblemDetail} RFC 9457 con el estado HTTP del {@link ErrorCode}.
 *
 * <p>Además del código y del detalle (mensaje seguro para el usuario, nunca información interna)
 * admite:
 * <ul>
 *   <li><b>extensiones</b>: propiedades adicionales que se publican en el nivel superior del JSON,
 *       p. ej. {@code {action, context}} para {@link ErrorCode#OVERRIDE_REQUIRED};</li>
 *   <li><b>errors</b>: lista de {@link FieldErrorDetail} para errores de validación hechos a mano
 *       (p. ej. un campo de {@code sort} no permitido).</li>
 * </ul>
 * Las extensiones no pueden usar nombres reservados ({@code type, title, status, detail, instance,
 * code, traceId, errors}); eso se verifica al construir para detectar el error en desarrollo.
 *
 * <p>Es no verificada (RuntimeException) para que las transacciones hagan rollback por defecto.
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final List<String> RESERVED =
            List.of("type", "title", "status", "detail", "instance", "code", "traceId", "errors");

    private final ErrorCode code;
    private final transient Map<String, Object> extensions;
    private final transient List<FieldErrorDetail> errors;

    /**
     * Crea una excepción con código y detalle.
     *
     * @param code código del catálogo (determina estado HTTP, title y type)
     * @param detail explicación para el usuario; si es nula se usa el título del código
     */
    public BusinessException(ErrorCode code, String detail) {
        this(code, detail, Map.of(), List.of());
    }

    /**
     * Crea una excepción con extensiones, p. ej. {@code Map.of("action", "...", "context", ...)}.
     *
     * @param code código del catálogo
     * @param detail explicación para el usuario
     * @param extensions propiedades extra del problema (copia inmutable); sin nombres reservados
     * @throws IllegalArgumentException si alguna clave es un nombre reservado
     */
    public BusinessException(ErrorCode code, String detail, Map<String, Object> extensions) {
        this(code, detail, extensions, List.of());
    }

    /**
     * Crea una excepción con extensiones y errores por campo.
     *
     * @param code código del catálogo
     * @param detail explicación para el usuario
     * @param extensions propiedades extra del problema; sin nombres reservados
     * @param errors errores por campo para {@code errors[]}
     * @throws NullPointerException si {@code code}, {@code extensions} o {@code errors} son nulos
     * @throws IllegalArgumentException si alguna extensión usa un nombre reservado
     */
    public BusinessException(ErrorCode code, String detail, Map<String, Object> extensions,
                             List<FieldErrorDetail> errors) {
        super(detail != null ? detail : Objects.requireNonNull(code, "code").title());
        this.code = Objects.requireNonNull(code, "code");
        Objects.requireNonNull(extensions, "extensions");
        extensions.keySet().stream().filter(RESERVED::contains).findFirst().ifPresent(key -> {
            throw new IllegalArgumentException("Nombre de extensión reservado: " + key);
        });
        this.extensions = Map.copyOf(extensions);
        this.errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
    }

    /** Código del catálogo F.1. */
    public ErrorCode getCode() {
        return code;
    }

    /** Propiedades extra del problema (inmutable; vacío si no hay). */
    public Map<String, Object> getExtensions() {
        return extensions;
    }

    /** Errores por campo (inmutable; vacío si no hay). */
    public List<FieldErrorDetail> getErrors() {
        return errors;
    }
}
