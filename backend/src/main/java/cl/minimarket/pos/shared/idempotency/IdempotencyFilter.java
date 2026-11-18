package cl.minimarket.pos.shared.idempotency;

import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.FilterExceptionForwarder;
import cl.minimarket.pos.shared.context.HandlerMethodResolver;
import cl.minimarket.pos.shared.context.StoreContextFilter;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aplica la idempotencia declarativa a los métodos de controlador anotados con {@link Idempotent}
 * (ADR-14, A.5).
 *
 * <p>Flujo por request:
 * <ol>
 *   <li>Si el destino no es {@code @Idempotent}, pasa sin tocar nada.</li>
 *   <li>Valida el header {@code Idempotency-Key} (UUID) y exige usuario autenticado; si no, error
 *       400/401 estándar.</li>
 *   <li>Lee el cuerpo (acotado por {@code pos.idempotency.max-body-size}), calcula la huella con
 *       {@link RequestFingerprint} y llama a {@link IdempotencyService#begin}.</li>
 *   <li>Si había respuesta guardada, la escribe (con {@code Idempotent-Replayed: true}) y termina
 *       sin ejecutar el controlador.</li>
 *   <li>Si no, ejecuta la cadena capturando la respuesta. Con estado 2xx guarda estado y cuerpo
 *       ({@link IdempotencyService#complete}); con cualquier otro estado, o una excepción, libera
 *       la clave ({@link IdempotencyService#release}) para que el cliente pueda reintentar.</li>
 * </ol>
 *
 * <p><b>Decisión de diseño: filtro y no aspecto/interceptor.</b> Solo un filtro ve la respuesta
 * serializada (estado y bytes finales, incluidos los de {@code ResponseBodyAdvice}) y el cuerpo
 * crudo del request (para el hash) sin acoplarse a las firmas de los controladores. El filtro se
 * ejecuta <i>fuera</i> de la transacción de negocio (que abre la capa application), como exige la
 * garantía de {@code REQUIRES_NEW}.
 *
 * <p><b>Orden:</b> {@value #ORDER}, tras Spring Security (-100, para conocer al usuario) y tras
 * {@link StoreContextFilter} (-90, para no registrar claves de requests a un local no permitido).
 *
 * <p><b>Fallo al guardar la respuesta:</b> si la request ya tuvo éxito pero {@code complete} falla
 * (p. ej. la BD cayó), el filtro registra un error y entrega igualmente la respuesta; la clave queda
 * {@code IN_PROGRESS} hasta su timeout, lo que bloquea reintentos duplicados (falla seguro).
 *
 * <p>Límites: solo requests síncronas (no se aplica en el despacho asíncrono) y respuestas JSON o
 * sin cuerpo; para otro tipo de contenido se guarda el estado sin cuerpo.
 */
@Component
@Order(IdempotencyFilter.ORDER)
public class IdempotencyFilter extends OncePerRequestFilter {

    /** Header obligatorio de los endpoints [I]. */
    public static final String KEY_HEADER = "Idempotency-Key";

    /** Header que se agrega a las respuestas reproducidas desde la clave guardada. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** Orden del filtro: después de la autenticación y del {@link StoreContextFilter}. */
    public static final int ORDER = -80;

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final HandlerMethodResolver handlerResolver;
    private final IdempotencyService service;
    private final IdempotencyProperties properties;
    private final FilterExceptionForwarder errors;

    /**
     * @param handlerResolver resuelve la anotación del controlador destino
     * @param service registro de claves
     * @param properties límite de tamaño del cuerpo
     * @param errors traductor de excepciones de filtro a {@code ProblemDetail}
     */
    public IdempotencyFilter(HandlerMethodResolver handlerResolver, IdempotencyService service,
                             IdempotencyProperties properties, FilterExceptionForwarder errors) {
        this.handlerResolver = handlerResolver;
        this.service = service;
        this.properties = properties;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isIdempotent(request)) {
            chain.doFilter(request, response);
            return;
        }
        CachedBodyRequest cachedRequest;
        UUID key;
        Long userId;
        String endpoint;
        String hash;
        Optional<StoredResponse> replay;
        try {
            key = parseKey(request);
            userId = CurrentUserId.find().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED,
                    "Debe iniciar sesión para continuar."));
            cachedRequest = new CachedBodyRequest(request, readBody(request));
            String path = pathOf(request);
            endpoint = IdempotencyService.endpointOf(request.getMethod(), path);
            hash = RequestFingerprint.of(request.getMethod(), path, request.getQueryString(), userId,
                    cachedRequest.body());
            replay = service.begin(key, userId, endpoint, hash);
        } catch (BusinessException e) {
            errors.forward(request, response, e);
            return;
        }
        if (replay.isPresent()) {
            writeReplay(response, replay.get());
            return;
        }
        executeAndStore(cachedRequest, response, chain, key, userId, endpoint);
    }

    private void executeAndStore(CachedBodyRequest request, HttpServletResponse response, FilterChain chain,
                                 UUID key, Long userId, String endpoint) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(request, wrapper);
            stored = storeIfSuccessful(wrapper, key, userId, endpoint);
        } finally {
            if (!stored) {
                releaseQuietly(key, userId, endpoint, wrapper.getStatus());
            }
        }
        wrapper.copyBodyToResponse();
    }

    /**
     * @return {@code true} si la clave ya no debe liberarse: se guardó la respuesta, o falló el
     *         guardado de una request exitosa (se deja en curso a propósito, ver la descripción de la clase)
     */
    private boolean storeIfSuccessful(ContentCachingResponseWrapper wrapper, UUID key, Long userId, String endpoint) {
        int status = wrapper.getStatus();
        if (status < 200 || status > 299) {
            return false;
        }
        try {
            if (!service.complete(key, userId, endpoint, status, jsonBodyOf(wrapper), replayableHeaders(wrapper))) {
                LOG.warn("La clave de idempotencia {} ya no estaba en curso al guardar la respuesta", key);
            }
        } catch (RuntimeException e) {
            LOG.error("No se pudo guardar la respuesta idempotente de la clave {}; queda en curso", key, e);
        }
        return true;
    }

    private void releaseQuietly(UUID key, Long userId, String endpoint, int status) {
        try {
            service.release(key, userId, endpoint);
            LOG.debug("Clave de idempotencia {} liberada (estado {})", key, status);
        } catch (RuntimeException e) {
            LOG.error("No se pudo liberar la clave de idempotencia {}", key, e);
        }
    }

    private static String jsonBodyOf(ContentCachingResponseWrapper wrapper) {
        byte[] content = wrapper.getContentAsByteArray();
        String contentType = wrapper.getContentType();
        if (content.length == 0 || contentType == null) {
            return null;
        }
        MediaType type = MediaType.parseMediaType(contentType);
        boolean json = MediaType.APPLICATION_JSON.isCompatibleWith(type)
                || "json".equalsIgnoreCase(type.getSubtype()) || type.getSubtype().endsWith("+json");
        return json ? new String(content, StandardCharsets.UTF_8) : null;
    }

    /** Cabeceras de la respuesta original que se guardan para reponerlas en el replay. */
    private static Map<String, String> replayableHeaders(ContentCachingResponseWrapper wrapper) {
        String location = wrapper.getHeader(HttpHeaders.LOCATION);
        return location == null ? Map.of() : Map.of(HttpHeaders.LOCATION, location);
    }

    private static void writeReplay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        response.setHeader(REPLAYED_HEADER, "true");
        stored.headers().forEach(response::setHeader);
        if (stored.body() != null) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(stored.body().getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean isIdempotent(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return false;
        }
        Optional<HandlerMethod> handler = handlerResolver.resolve(request);
        return handler.isPresent() && HandlerMethodResolver.hasAnnotation(handler.get(), Idempotent.class);
    }

    private static UUID parseKey(HttpServletRequest request) {
        String raw = request.getHeader(KEY_HEADER);
        if (raw == null || raw.isBlank()) {
            throw invalid("REQUIRED", "El header " + KEY_HEADER + " es obligatorio.");
        }
        String value = raw.trim();
        if (!UUID_PATTERN.matcher(value).matches()) {
            throw invalid("INVALID_FORMAT", "El header " + KEY_HEADER + " debe ser un UUID.");
        }
        return UUID.fromString(value);
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        long limit = properties.maxBodySize().toBytes();
        byte[] body = request.getInputStream().readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
        if (body.length > limit) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El cuerpo de la solicitud excede el tamaño máximo permitido.");
        }
        return body;
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, Map.of(),
                List.of(new FieldErrorDetail(KEY_HEADER, code, message)));
    }
}
