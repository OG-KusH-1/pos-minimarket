package cl.minimarket.pos.shared.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Propiedades de idempotencia (prefijo {@code pos.idempotency}). Los defaults viven en código
 * (regla H.1); {@code ttl} ya figura en {@code application.yml}.
 *
 * @param ttl vigencia de una clave desde su creación (A.5: 24 h). Pasado ese tiempo la clave
 *        deja de proteger y el job de purga la elimina
 * @param inProgressTimeout tiempo tras el cual un registro {@code IN_PROGRESS} se considera
 *        abandonado (el proceso murió a mitad de la request) y puede reutilizarse. Debe ser mayor
 *        que el timeout máximo de una request; si no, un reintento podría ejecutarse en paralelo
 * @param maxBodySize tamaño máximo del cuerpo que el filtro lee en memoria para calcular el hash
 */
@ConfigurationProperties(prefix = "pos.idempotency")
public record IdempotencyProperties(
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("5m") Duration inProgressTimeout,
        @DefaultValue("1MB") DataSize maxBodySize) {

    /** Valida que los valores sean positivos (falla el arranque con una configuración absurda). */
    public IdempotencyProperties {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("pos.idempotency.ttl debe ser positivo");
        }
        if (inProgressTimeout.isZero() || inProgressTimeout.isNegative()) {
            throw new IllegalArgumentException("pos.idempotency.in-progress-timeout debe ser positivo");
        }
        if (maxBodySize.toBytes() <= 0) {
            throw new IllegalArgumentException("pos.idempotency.max-body-size debe ser positivo");
        }
    }
}
