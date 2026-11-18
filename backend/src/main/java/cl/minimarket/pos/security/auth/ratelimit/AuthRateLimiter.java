package cl.minimarket.pos.security.auth.ratelimit;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import io.github.bucket4j.Bucket;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Componente reutilizable de rate limiting en memoria con Bucket4j por IP y por usuario objetivo
 * (ADR-21).
 *
 * <p>Lo utiliza T21 en {@code POST /auth/login} y queda disponible para T26 en
 * {@code POST /auth/overrides} y {@code POST /auth/pin-login}.
 */
@Component
@EnableConfigurationProperties({RateLimitProperties.class, LockoutProperties.class})
public class AuthRateLimiter {

    private final RateLimitProperties properties;
    private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> userBuckets = new ConcurrentHashMap<>();

    /**
     * @param properties límites configurados por minuto para IP y usuario objetivo
     */
    public AuthRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    /**
     * Verifica y consume un token en los buckets de IP y de usuario objetivo para el endpoint dado.
     *
     * @param scopeKey identificador del flujo/endpoint (p. ej. {@code "login"}, {@code "override"}, {@code "pin-login"})
     * @param ip dirección IP del cliente; si es nula o en blanco se omite el bucket por IP
     * @param targetUsername nombre del usuario objetivo; si es nulo o en blanco se omite el bucket por usuario
     * @throws BusinessException 429 {@code RATE_LIMITED} si cualquiera de los dos límites fue superado
     */
    public void checkAndConsume(String scopeKey, String ip, String targetUsername) {
        if (ip != null && !ip.isBlank()) {
            String ipKey = scopeKey + ":ip:" + ip.trim();
            Bucket ipBucket = ipBuckets.computeIfAbsent(ipKey, k -> createBucket(properties.perIpPerMinute()));
            if (!ipBucket.tryConsume(1)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED,
                        "Demasiados intentos desde esta dirección IP. Intenta nuevamente en un minuto.");
            }
        }
        if (targetUsername != null && !targetUsername.isBlank()) {
            String userKey = scopeKey + ":user:" + targetUsername.trim().toLowerCase(Locale.ROOT);
            Bucket userBucket = userBuckets.computeIfAbsent(userKey, k -> createBucket(properties.perUserPerMinute()));
            if (!userBucket.tryConsume(1)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED,
                        "Demasiados intentos para este usuario. Intenta nuevamente en un minuto.");
            }
        }
    }

    private static Bucket createBucket(int capacityPerMinute) {
        int safeCapacity = Math.max(1, capacityPerMinute);
        return Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(safeCapacity)
                        .refillIntervally(safeCapacity, Duration.ofMinutes(1)))
                .build();
    }
}
