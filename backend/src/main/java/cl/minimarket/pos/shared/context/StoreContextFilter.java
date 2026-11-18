package cl.minimarket.pos.shared.context;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Determina el local y la terminal activos de cada request y los deja en {@link StoreContext}
 * (ADR-12, RN-27).
 *
 * <p>Algoritmo por request:
 * <ol>
 *   <li>Las preflight {@code OPTIONS} y las requests sin método de controlador (404, 405) pasan
 *       sin tocar: las atiende CORS o el manejador de errores normal.</li>
 *   <li>Si el endpoint es global (ver abajo) no se exige ni se lee ningún header.</li>
 *   <li>{@code X-Store-Id} es obligatorio; {@code X-Terminal-Id} lo es solo con
 *       {@link RequiresTerminal}. Un header ausente, repetido o que no sea un entero positivo
 *       produce 400 {@code VALIDATION_ERROR} con el detalle en {@code errors[]}.</li>
 *   <li>Sin usuario identificable: 401 {@code UNAUTHENTICATED} (ver "Orden y seguridad"). Con usuario ({@link CurrentUserId}) se invoca
 *       {@link StoreAccessValidator#requireAccess}; una denegación sale como 403
 *       {@code STORE_ACCESS_DENIED} (o 422 {@code TERMINAL_INVALID}), tal como la lance el puerto.</li>
 *   <li>Se activa el {@link StoreContext} (y el MDC {@code storeId}/{@code terminalId}; también
 *       {@code userId} si hay usuario) durante el resto de la cadena y se limpia siempre al final.</li>
 * </ol>
 *
 * <p><b>Marcado de endpoints globales.</b> Un endpoint es global si su método o controlador lleva
 * {@link GlobalEndpoint}, o si su ruta coincide con alguno de los patrones de
 * {@link StoreContextProperties#globalPaths()} (para rutas sin controlador propio o previas a la
 * autenticación). El default es "no global": olvidar la anotación falla cerrado (400), no abre un
 * endpoint sin local.
 *
 * <p><b>Orden y seguridad (contrato con T21).</b> El filtro debe ejecutarse <i>después</i> de la
 * autenticación para ver al usuario; por eso su orden ({@value #ORDER}) queda justo tras la cadena
 * de Spring Security (orden -100). Mientras security no exista no hay usuario autenticado: el filtro
 * lo rechaza con 401 salvo con {@code allow-anonymous=true}. Cuando T21 lo implemente, (1) debe definir un
 * bean {@link StoreAccessValidator}, (2) puede reordenar este filtro dentro de su
 * {@code SecurityFilterChain} si lo prefiere, y (3) debe garantizar que ningún endpoint no global
 * sea accesible de forma anónima. El filtro falla <b>cerrado</b>: en un endpoint no global, una request
 * sin autenticación recibe 401 {@code UNAUTHENTICATED} (salvo que {@code pos.store-context.allow-anonymous}
 * sea {@code true}, solo para pruebas), y una autenticación real cuyo id no se puede extraer (principal
 * que no implementa {@link UserIdentity} y {@code getName()} no numérico) también recibe 401, porque el
 * local no podría validarse. Si hay usuario pero ningún bean validador, falla con un error de servidor.
 *
 * <p>Las excepciones se entregan al {@code GlobalExceptionHandler} mediante
 * {@link FilterExceptionForwarder}, por lo que el formato del error es el estándar.
 */
@Component
@Order(StoreContextFilter.ORDER)
@EnableConfigurationProperties(StoreContextProperties.class)
public class StoreContextFilter extends OncePerRequestFilter {

    /** Header con el local activo. */
    public static final String STORE_HEADER = "X-Store-Id";

    /** Header con la terminal activa. */
    public static final String TERMINAL_HEADER = "X-Terminal-Id";

    /** Clave del MDC con el usuario autenticado (la comparten los filtros de shared). */
    public static final String MDC_USER_ID = "userId";

    /** Orden del filtro: inmediatamente después de la cadena de Spring Security (-100). */
    public static final int ORDER = -90;

    private static final Logger LOG = LoggerFactory.getLogger(StoreContextFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final HandlerMethodResolver handlerResolver;
    private final ObjectProvider<StoreAccessValidator> validator;
    private final StoreContextProperties properties;
    private final FilterExceptionForwarder errors;

    /**
     * @param handlerResolver resuelve las anotaciones del controlador destino
     * @param validator puerto de validación; opcional hasta que security lo implemente (T21)
     * @param properties rutas globales configurables
     * @param errors traductor de excepciones de filtro a {@code ProblemDetail}
     */
    public StoreContextFilter(HandlerMethodResolver handlerResolver, ObjectProvider<StoreAccessValidator> validator,
                              StoreContextProperties properties, FilterExceptionForwarder errors) {
        this.handlerResolver = handlerResolver;
        this.validator = validator;
        this.properties = properties;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        StoreContext context;
        Optional<Long> userId = CurrentUserId.find();
        try {
            context = resolveContext(request, userId);
        } catch (BusinessException e) {
            errors.forward(request, response, e);
            return;
        }
        if (context == null) {
            chain.doFilter(request, response);
            return;
        }
        String previousUserMdc = MDC.get(MDC_USER_ID);
        userId.ifPresent(id -> MDC.put(MDC_USER_ID, String.valueOf(id)));
        try (StoreContext.Scope ignored = StoreContext.bind(context)) {
            chain.doFilter(request, response);
        } finally {
            if (previousUserMdc == null) {
                MDC.remove(MDC_USER_ID);
            } else {
                MDC.put(MDC_USER_ID, previousUserMdc);
            }
        }
    }

    /**
     * Decide si la request lleva contexto y, de ser así, lo construye y valida.
     *
     * @return el contexto validado, o {@code null} si la request no lleva contexto (global, OPTIONS o sin handler)
     * @throws BusinessException 400 por headers inválidos, o lo que lance el {@link StoreAccessValidator}
     */
    private StoreContext resolveContext(HttpServletRequest request, Optional<Long> userId) {
        if (HttpMethod.OPTIONS.matches(request.getMethod()) || isGlobalPath(request)) {
            return null;
        }
        Optional<HandlerMethod> handler = handlerResolver.resolve(request);
        if (handler.isEmpty() || HandlerMethodResolver.hasAnnotation(handler.get(), GlobalEndpoint.class)) {
            return null;
        }
        // Falla cerrado: autenticado sin id extraíble (principal fuera de contrato) o anónimo con el modo
        // permisivo apagado nunca deja pasar un local sin validar.
        if (userId.isEmpty() && (CurrentUserId.isAuthenticated() || !properties.allowAnonymous())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Debe iniciar sesión para continuar.");
        }
        boolean terminalRequired = HandlerMethodResolver.hasAnnotation(handler.get(), RequiresTerminal.class);
        Long storeId = readId(request, STORE_HEADER, true);
        Long terminalId = readId(request, TERMINAL_HEADER, terminalRequired);
        if (userId.isPresent()) {
            requireValidator().requireAccess(userId.get(), storeId, terminalId);
        } else {
            // Solo se llega aquí con allow-anonymous=true y sin autenticación (ver el chequeo anterior).
            LOG.debug("Request anónima permitida por pos.store-context.allow-anonymous: local {} sin validar", storeId);
        }
        return new StoreContext(storeId, terminalId);
    }

    private StoreAccessValidator requireValidator() {
        StoreAccessValidator found = validator.getIfAvailable();
        if (found == null) {
            throw new IllegalStateException(
                    "Hay un usuario autenticado pero no existe ningún bean StoreAccessValidator (lo implementa security)");
        }
        return found;
    }

    private boolean isGlobalPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        List<String> patterns = properties.globalPaths();
        return patterns.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    /**
     * Lee un header numérico.
     *
     * @param required si es {@code true} un header ausente es un error; si no, devuelve {@code null}
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si falta (y es obligatorio), llega repetido
     *         o no es un entero positivo
     */
    private static Long readId(HttpServletRequest request, String header, boolean required) {
        List<String> values = Collections.list(request.getHeaders(header));
        if (values.isEmpty() || values.getFirst().isBlank()) {
            if (required) {
                throw invalid(header, "REQUIRED", "El header " + header + " es obligatorio.");
            }
            return null;
        }
        if (values.size() > 1) {
            throw invalid(header, "DUPLICATED", "El header " + header + " no puede repetirse.");
        }
        String raw = values.getFirst().trim();
        if (raw.length() > 18 || !raw.chars().allMatch(Character::isDigit) || Long.parseLong(raw) <= 0) {
            throw invalid(header, "INVALID_FORMAT", "El header " + header + " debe ser un entero positivo.");
        }
        return Long.valueOf(raw);
    }

    private static BusinessException invalid(String header, String code, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, Map.of(),
                List.of(new FieldErrorDetail(header, code, message)));
    }
}
