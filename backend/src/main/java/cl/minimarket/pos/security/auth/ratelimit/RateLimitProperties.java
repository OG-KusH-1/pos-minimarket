package cl.minimarket.pos.security.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades de rate limiting con Bucket4j ({@code pos.security.rate-limit}, ADR-21).
 *
 * @param perIpPerMinute máximo de intentos por minuto desde una misma IP (por defecto 10)
 * @param perUserPerMinute máximo de intentos por minuto contra un mismo usuario objetivo (por defecto 5)
 */
@ConfigurationProperties(prefix = "pos.security.rate-limit")
public record RateLimitProperties(
        @DefaultValue("10") int perIpPerMinute,
        @DefaultValue("5") int perUserPerMinute
) {
}
