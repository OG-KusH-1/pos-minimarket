package cl.minimarket.pos.shared.persistence;

import cl.minimarket.pos.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.ToIntFunction;
import java.time.Instant;

/**
 * Jobs {@code @Scheduled} de purga (ADR-31: una sola instancia, sin Quartz ni ShedLock). Cada job
 * delega en {@link DataPurgeService}, registra cuántas filas tocó y <b>nunca propaga</b> una
 * excepción: un fallo de un job se registra y se reintenta en la siguiente ejecución sin afectar a
 * los demás.
 *
 * <p>Horarios: expresiones cron evaluadas en la zona de negocio ({@code pos.business-zone}),
 * configurables con estas propiedades (default entre paréntesis; formato cron de Spring, 6 campos):
 * <ul>
 *   <li>{@code pos.purge.login-attempts-cron} ({@code 0 15 3 * * *}, diario 03:15)</li>
 *   <li>{@code pos.purge.refresh-tokens-cron} ({@code 0 30 3 * * *}, diario 03:30)</li>
 *   <li>{@code pos.purge.idempotency-keys-cron} ({@code 0 5 * * * *}, cada hora al minuto 5)</li>
 *   <li>{@code pos.purge.stored-files-cron} ({@code 0 45 3 * * *}, diario 03:45)</li>
 * </ul>
 * Usar {@code -} como valor desactiva ese job. {@code pos.purge.enabled=false} desactiva todos
 * (los beans no se crean). Las retenciones están en {@link PurgeProperties}.
 *
 * <p>Se usan {@code cron} y no {@code fixedDelay} para que la carga caiga fuera del horario de
 * atención, y porque el cron evalúa en la zona de negocio.
 */
@Component
@ConditionalOnProperty(prefix = "pos.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataPurgeJobs {

    private static final Logger LOG = LoggerFactory.getLogger(DataPurgeJobs.class);

    private final DataPurgeService purge;
    private final BusinessClock clock;

    /**
     * @param purge operaciones de purga
     * @param clock reloj de negocio
     */
    public DataPurgeJobs(DataPurgeService purge, BusinessClock clock) {
        this.purge = purge;
        this.clock = clock;
    }

    /** Purga {@code login_attempts} antiguos (90 días). */
    @Scheduled(cron = "${pos.purge.login-attempts-cron:0 15 3 * * *}", zone = "${pos.business-zone:America/Santiago}")
    public void purgeLoginAttempts() {
        run("login_attempts", purge::purgeLoginAttempts);
    }

    /** Purga refresh tokens vencidos o revocados hace más de la retención. */
    @Scheduled(cron = "${pos.purge.refresh-tokens-cron:0 30 3 * * *}", zone = "${pos.business-zone:America/Santiago}")
    public void purgeRefreshTokens() {
        run("refresh_tokens", purge::purgeRefreshTokens);
    }

    /** Purga claves de idempotencia vencidas (TTL). */
    @Scheduled(cron = "${pos.purge.idempotency-keys-cron:0 5 * * * *}", zone = "${pos.business-zone:America/Santiago}")
    public void purgeIdempotencyKeys() {
        run("idempotency_keys", purge::purgeIdempotencyKeys);
    }

    /** Marca como eliminados los archivos huérfanos (el binario lo borra el módulo files). */
    @Scheduled(cron = "${pos.purge.stored-files-cron:0 45 3 * * *}", zone = "${pos.business-zone:America/Santiago}")
    public void markOrphanStoredFiles() {
        run("stored_files", purge::markOrphanStoredFiles);
    }

    private void run(String target, ToIntFunction<Instant> operation) {
        try {
            int affected = operation.applyAsInt(clock.now());
            LOG.info("Purga de {}: {} filas", target, affected);
        } catch (RuntimeException e) {
            LOG.error("Falló la purga de {}; se reintentará en la próxima ejecución", target, e);
        }
    }
}
