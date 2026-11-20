package cl.minimarket.pos.reporting.aggregate;

import cl.minimarket.pos.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Job de las 02:00 que recalcula el agregado {@code daily_product_sales} (ADR-31 y
 * MODELO_DATOS B.11: "el job de las 02:00 recalcula los últimos 3 días").
 *
 * <p>Sigue el patrón de los demás jobs del proyecto ({@code SuspendedSaleExpirationJob},
 * {@code DataPurgeJobs}): {@code @Scheduled} en una sola instancia, expresión cron evaluada en la
 * zona de negocio ({@code pos.business-zone}), desactivable por propiedad y <b>nunca propaga
 * excepciones</b>: un fallo se registra y la corrida de la madrugada siguiente reintenta los mismos
 * días, que es justamente la tolerancia que da la ventana de 3 días.
 *
 * <p>Configuración (defaults entre paréntesis):
 * <ul>
 *   <li>{@code pos.jobs.daily-aggregates-cron} ({@code 0 0 2 * * *}, diario 02:00): expresión
 *       ya definida en {@code application.yml} (ADR-31).</li>
 *   <li>{@code pos.jobs.daily-aggregates.enabled} ({@code true}): crea o no el bean.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
        prefix = "pos.jobs.daily-aggregates",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DailySalesAggregateJob {

    private static final Logger log = LoggerFactory.getLogger(DailySalesAggregateJob.class);

    private final DailySalesAggregateService aggregateService;
    private final BusinessClock businessClock;

    public DailySalesAggregateJob(DailySalesAggregateService aggregateService, BusinessClock businessClock) {
        this.aggregateService = aggregateService;
        this.businessClock = businessClock;
    }

    /**
     * Recalcula el agregado de los últimos 3 días (más los días tocados por devoluciones
     * recientes, ver {@link DailySalesAggregateService#refreshRecentDays(LocalDate)}).
     */
    @Scheduled(
            cron = "${pos.jobs.daily-aggregates-cron:0 0 2 * * *}",
            zone = "${pos.business-zone:America/Santiago}"
    )
    public void refreshDailyProductSales() {
        try {
            LocalDate runDate = businessClock.businessDate();
            int rows = aggregateService.refreshRecentDays(runDate);
            log.info("Agregado diario de ventas recalculado (corrida del {}): {} filas", runDate, rows);
        } catch (RuntimeException ex) {
            log.error("Error recalculando el agregado diario de ventas; se reintentará en la próxima corrida", ex);
        }
    }
}
