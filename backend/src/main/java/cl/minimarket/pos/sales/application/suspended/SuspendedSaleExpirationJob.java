package cl.minimarket.pos.sales.application.suspended;

import cl.minimarket.pos.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Job programado que elimina ventas suspendidas cuyo TTL ya venció ({@code PLAN.md} ADR-31 y
 * {@code MODELO_DATOS.md} B.9).
 *
 * <p>Se ejecuta fuera de contexto HTTP y sin {@code StoreContext}, operando transversalmente
 * sobre todos los locales con {@code FOR UPDATE SKIP LOCKED}. Puede deshabilitarse mediante la
 * propiedad {@code pos.jobs.suspended-expiration.enabled=false}.
 */
@Component
@ConditionalOnProperty(
        prefix = "pos.jobs.suspended-expiration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SuspendedSaleExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(SuspendedSaleExpirationJob.class);

    private final SuspendedSaleService suspendedSaleService;
    private final BusinessClock businessClock;

    public SuspendedSaleExpirationJob(
            SuspendedSaleService suspendedSaleService,
            BusinessClock businessClock
    ) {
        this.suspendedSaleService = suspendedSaleService;
        this.businessClock = businessClock;
    }

    /**
     * Purga todas las ventas suspendidas con {@code expires_at <= now}.
     */
    @Scheduled(
            cron = "${pos.jobs.suspended-expiration-cron:0 */5 * * * *}",
            zone = "${pos.business-zone:America/Santiago}"
    )
    public void expireSuspendedSales() {
        try {
            Instant now = businessClock.now();
            int removed = suspendedSaleService.purgeExpired(now);
            if (removed > 0) {
                log.info("Expiración de ventas suspendidas completada: {} filas eliminadas", removed);
            }
        } catch (RuntimeException ex) {
            log.error("Error ejecutando expiración programada de ventas suspendidas", ex);
        }
    }
}
