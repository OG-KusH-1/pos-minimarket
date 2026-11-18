package cl.minimarket.pos.shared.idempotency;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registro y consulta de claves de idempotencia (ADR-14, A.5 "Idempotencia").
 *
 * <p>Ciclo de vida de una clave:
 * <ol>
 *   <li>{@link #begin}: inserta la clave como {@code IN_PROGRESS} en <b>su propia transacción</b>
 *       ({@code REQUIRES_NEW}), de modo que el registro es visible para otras requests antes de que
 *       termine la transacción de negocio y sobrevive a su rollback. Si la clave ya existe decide:
 *       mismo hash y {@code COMPLETED} devuelve la respuesta guardada; {@code IN_PROGRESS} lanza
 *       409 {@code IDEMPOTENCY_IN_PROGRESS}; hash distinto lanza 409 {@code IDEMPOTENCY_CONFLICT}.</li>
 *   <li>{@link #complete}: guarda estado y cuerpo de la respuesta (también en su propia transacción).</li>
 *   <li>{@link #release}: si la request falló, elimina el {@code IN_PROGRESS} para permitir reintentar.</li>
 * </ol>
 *
 * <p><b>Por qué {@code REQUIRES_NEW} en todo:</b> el estado de la clave nunca debe depender de la
 * transacción del llamador (si el negocio hace rollback, la clave debe poder liberarse; si hace
 * commit, la clave debe poder completarse). El servicio no debe invocarse desde dentro de la
 * transacción de negocio de la misma request; lo usa {@link IdempotencyFilter}, que corre fuera de
 * ella. Para uso programático (p. ej. un caso de uso que no pase por HTTP) se puede llamar
 * directamente con el mismo contrato.
 *
 * <p><b>Reutilización de claves inservibles:</b> una clave vencida (TTL) o un {@code IN_PROGRESS}
 * abandonado (más antiguo que {@link IdempotencyProperties#inProgressTimeout()}) se descartan al
 * llegar un nuevo intento, sin esperar a la purga. Esto evita bloquear al cliente hasta 24 h cuando
 * un proceso murió a mitad de una request.
 *
 * <p><b>Doble ejecución y UQ del negocio.</b> El timeout de retoma se valida al arrancar contra
 * {@code spring.transaction.default-timeout} (debe ser mayor), pero la idempotencia por clave no es
 * una garantía absoluta (p. ej. un {@code IN_PROGRESS} retomado tras un caído). Los casos de uso con
 * una columna {@code idempotency_key} UNIQUE (ventas, devoluciones, abonos...) son la última barrera:
 * deben <b>traducir la violación de esa UQ en la devolución del recurso ya creado</b> (consultarlo
 * por la clave y responder como si fuera el original), nunca en un error al cliente.
 *
 * <p>Las cabeceras repuestas en el replay se guardan dentro del {@code jsonb} (ver {@link ResponseEnvelope}).
 *
 * <p>Concurrencia: dos requests simultáneas con la misma clave compiten por el {@code INSERT}
 * (índice único); gana una y la otra ve {@code IN_PROGRESS}.
 */
@Service
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyService {

    /** Longitud máxima de {@code idempotency_keys.endpoint}. */
    static final int ENDPOINT_MAX_LENGTH = 100;

    /** Propiedad de Spring con el timeout por defecto de las transacciones. */
    static final String TX_TIMEOUT_PROPERTY = "spring.transaction.default-timeout";

    /** Timeout asumido si la propiedad no está definida (el mismo que fija application.yml). */
    static final Duration DEFAULT_TX_TIMEOUT = Duration.ofSeconds(60);

    /** Intentos máximos del ciclo insertar/leer, para tolerar que otra request libere la clave entre medias. */
    private static final int MAX_ATTEMPTS = 3;

    private final IdempotencyKeyRepository repository;
    private final IdempotencyProperties properties;
    private final BusinessClock clock;

    /**
     * @param repository acceso a la tabla
     * @param properties TTL y timeout de {@code IN_PROGRESS}
     * @param clock reloj de negocio (fijable en pruebas)
     * @param environment para leer {@code spring.transaction.default-timeout}
     * @throws IllegalStateException si {@code in-progress-timeout} no es mayor que el timeout de transacción
     */
    public IdempotencyService(IdempotencyKeyRepository repository, IdempotencyProperties properties,
                              BusinessClock clock, Environment environment) {
        requireInProgressTimeoutAbove(properties.inProgressTimeout(), environment.getProperty(
                TX_TIMEOUT_PROPERTY, Duration.class, DEFAULT_TX_TIMEOUT));
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Valida que un {@code IN_PROGRESS} no pueda considerarse abandonado mientras su transacción de
     * negocio todavía puede estar viva: si {@code inProgressTimeout} fuera menor o igual que el
     * timeout de transacción, un reintento podría retomar la clave y ejecutar el caso de uso dos
     * veces en paralelo.
     *
     * @param inProgressTimeout {@code pos.idempotency.in-progress-timeout}
     * @param transactionTimeout {@code spring.transaction.default-timeout}
     * @throws IllegalStateException si {@code inProgressTimeout <= transactionTimeout}
     */
    static void requireInProgressTimeoutAbove(Duration inProgressTimeout, Duration transactionTimeout) {
        if (inProgressTimeout.compareTo(transactionTimeout) <= 0) {
            throw new IllegalStateException("pos.idempotency.in-progress-timeout (" + inProgressTimeout
                    + ") debe ser mayor que spring.transaction.default-timeout (" + transactionTimeout + ")");
        }
    }

    /**
     * Identificador de endpoint que se guarda con la clave: {@code "<MÉTODO> <ruta>"}. Si excede
     * la columna (100) se trunca y se le agrega un sufijo hash para que rutas largas distintas no
     * colisionen.
     *
     * @param method método HTTP
     * @param path ruta de la request, sin query
     * @return texto de a lo sumo 100 caracteres
     */
    public static String endpointOf(String method, String path) {
        String endpoint = method.toUpperCase(Locale.ROOT) + " " + path;
        if (endpoint.length() <= ENDPOINT_MAX_LENGTH) {
            return endpoint;
        }
        String suffix = "#" + sha256Hex(endpoint).substring(0, 16);
        return endpoint.substring(0, ENDPOINT_MAX_LENGTH - suffix.length()) + suffix;
    }

    /**
     * Intenta tomar la clave para esta request.
     *
     * @param key valor del header {@code Idempotency-Key}
     * @param userId usuario autenticado
     * @param endpoint ver {@link #endpointOf(String, String)}
     * @param requestHash huella del request, ver {@link RequestFingerprint}
     * @return vacío si la clave es nueva y el llamador debe ejecutar el caso de uso (queda
     *         {@code IN_PROGRESS}); con valor si ya hay una respuesta guardada que se debe devolver tal cual
     * @throws BusinessException 409 {@code IDEMPOTENCY_IN_PROGRESS} si la primera request sigue en
     *         curso; 409 {@code IDEMPOTENCY_CONFLICT} si la clave se usó con otro request
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<StoredResponse> begin(UUID key, Long userId, String endpoint, String requestHash) {
        Instant now = clock.now();
        Instant staleBefore = now.minus(properties.inProgressTimeout());
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            repository.deleteUnusable(key, userId, endpoint, now, staleBefore);
            if (repository.insertInProgress(key, userId, endpoint, requestHash, now, now.plus(properties.ttl())) == 1) {
                return Optional.empty();
            }
            Optional<IdempotencyKey> existing = repository.find(key, userId, endpoint);
            if (existing.isPresent()) {
                return Optional.of(replayOrReject(existing.get(), requestHash));
            }
            // La fila desapareció entre el INSERT y la lectura (otra request la liberó): reintentar.
        }
        throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "La solicitud con esta Idempotency-Key aún se está procesando.");
    }

    /**
     * Guarda la respuesta de la request y marca la clave como {@code COMPLETED}.
     *
     * @param key clave tomada con {@link #begin}
     * @param userId usuario autenticado
     * @param endpoint mismo valor usado en {@link #begin}
     * @param responseStatus código HTTP de la respuesta (2xx)
     * @param responseBody cuerpo JSON como texto; {@code null} si no hubo cuerpo
     * @param headers cabeceras a reponer en el replay (p. ej. {@code Location}); vacío si no hay
     * @return {@code true} si se guardó; {@code false} si la clave ya no estaba {@code IN_PROGRESS}
     *         (p. ej. la purgaron por TTL durante una request excepcionalmente larga)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(UUID key, Long userId, String endpoint, int responseStatus, String responseBody,
                            Map<String, String> headers) {
        return repository.markCompleted(key, userId, endpoint, responseStatus,
                ResponseEnvelope.wrap(responseBody, headers)) == 1;
    }

    /**
     * Libera una clave {@code IN_PROGRESS} tras una request fallida, para que el cliente pueda
     * reintentar con la misma clave. No toca claves ya completadas.
     *
     * @param key clave tomada con {@link #begin}
     * @param userId usuario autenticado
     * @param endpoint mismo valor usado en {@link #begin}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID key, Long userId, String endpoint) {
        repository.deleteInProgress(key, userId, endpoint);
    }

    /**
     * Elimina las claves cuyo TTL venció (lo invoca el job de purga).
     *
     * @param now instante de referencia
     * @return número de claves eliminadas
     */
    @Transactional
    public int purgeExpired(Instant now) {
        return repository.deleteExpired(now);
    }

    private StoredResponse replayOrReject(IdempotencyKey existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "La Idempotency-Key ya se usó con una solicitud distinta.");
        }
        if (!IdempotencyKey.COMPLETED.equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "La solicitud con esta Idempotency-Key aún se está procesando.");
        }
        return ResponseEnvelope.unwrap(existing.getResponseStatus(), existing.getResponseBody());
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
