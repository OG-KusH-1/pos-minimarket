package cl.minimarket.pos.reporting.purchasing.application;

import cl.minimarket.pos.reporting.purchasing.PurchasesBySupplierRow;
import cl.minimarket.pos.reporting.purchasing.infrastructure.PurchasingReportRepository;
import cl.minimarket.pos.reporting.purchasing.infrastructure.PurchasingReportRepository.SupplierPurchaseAggregate;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Servicio de aplicación para el reporte de compras por proveedor ({@code HU-REP-08}).
 *
 * <p>Calcula órdenes emitidas, recepciones confirmadas, monto neto recibido, promedio real de días
 * de entrega y {@code fillRatePct = recibido / pedido × 100}.
 */
@Service
public class PurchasingReportService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int METRIC_SCALE = 2;

    private final PurchasingReportRepository repository;
    private final BusinessClock clock;

    public PurchasingReportService(PurchasingReportRepository repository, BusinessClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Genera el reporte de compras por proveedor para el filtro indicado.
     *
     * @param filter filtro de fechas obligatorio y locales
     * @return respuesta con filas por proveedor y totales globales ponderados
     */
    @Transactional(readOnly = true)
    public ReportResponse<PurchasesBySupplierRow> generate(ReportFilter filter) {
        Objects.requireNonNull(filter, "filter");
        filter.requireRange();

        List<Long> effectiveStoreIds = filter.storeIds().isEmpty()
                ? List.of(StoreContext.require().storeId())
                : filter.storeIds();
        ReportFilter effectiveFilter = new ReportFilter(filter.from(), filter.to(), effectiveStoreIds);

        Instant fromInstant = clock.startOfDay(effectiveFilter.from());
        Instant toExclusive = clock.startOfDay(effectiveFilter.to().plusDays(1));

        List<SupplierPurchaseAggregate> aggregates =
                repository.findSupplierAggregates(fromInstant, toExclusive, effectiveStoreIds);

        List<PurchasesBySupplierRow> rows = new ArrayList<>(aggregates.size());
        long totalOrders = 0L;
        long totalReceipts = 0L;
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal totalOrdered = BigDecimal.ZERO;
        BigDecimal totalReceived = BigDecimal.ZERO;
        BigDecimal totalLeadTimeSum = BigDecimal.ZERO;
        long totalLeadTimeSamples = 0L;

        for (SupplierPurchaseAggregate agg : aggregates) {
            BigDecimal netTotal = Rounding.clp(agg.netTotal());
            BigDecimal avgLeadTime = computeAvgLeadTime(agg.leadTimeDaysSum(), agg.leadTimeSamples());
            BigDecimal fillRatePct = computeFillRatePct(agg.qtyReceived(), agg.qtyOrdered());

            rows.add(new PurchasesBySupplierRow(
                    agg.supplierId(),
                    agg.name(),
                    agg.orders(),
                    agg.receipts(),
                    netTotal,
                    avgLeadTime,
                    fillRatePct));

            totalOrders += agg.orders();
            totalReceipts += agg.receipts();
            totalNet = totalNet.add(netTotal);
            totalOrdered = totalOrdered.add(agg.qtyOrdered());
            totalReceived = totalReceived.add(agg.qtyReceived());
            if (agg.leadTimeDaysSum() != null && agg.leadTimeSamples() > 0) {
                totalLeadTimeSum = totalLeadTimeSum.add(agg.leadTimeDaysSum());
                totalLeadTimeSamples += agg.leadTimeSamples();
            }
        }

        PurchasesBySupplierRow totals = new PurchasesBySupplierRow(
                null,
                "Total",
                totalOrders,
                totalReceipts,
                Rounding.clp(totalNet),
                computeAvgLeadTime(totalLeadTimeSum, totalLeadTimeSamples),
                computeFillRatePct(totalReceived, totalOrdered));

        return new ReportResponse<>(effectiveFilter, clock.now(), rows, totals);
    }

    private static BigDecimal computeAvgLeadTime(BigDecimal sumDays, long samples) {
        if (samples <= 0 || sumDays == null) {
            return null;
        }
        return sumDays.divide(BigDecimal.valueOf(samples), METRIC_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal computeFillRatePct(BigDecimal qtyReceived, BigDecimal qtyOrdered) {
        if (qtyOrdered == null || qtyOrdered.signum() <= 0) {
            return null;
        }
        return qtyReceived.divide(qtyOrdered, Rounding.INTERMEDIATE)
                .multiply(HUNDRED)
                .setScale(METRIC_SCALE, RoundingMode.HALF_UP);
    }
}
