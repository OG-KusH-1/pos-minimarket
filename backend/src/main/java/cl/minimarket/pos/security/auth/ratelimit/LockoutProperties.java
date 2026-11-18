package cl.minimarket.pos.security.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Propiedades de bloqueo de cuenta por intentos fallidos ({@code pos.security.lockout}, ADR-21).
 *
 * @param maxFailedAttempts número de intentos fallidos antes de bloquear (respaldo si no hay setting)
 * @param duration duración del bloqueo de cuenta (respaldo si no hay setting)
 */
@ConfigurationProperties(prefix = "pos.security.lockout")
public record LockoutProperties(
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("15m") Duration duration
) {
}
