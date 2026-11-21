package cl.minimarket.pos.reporting.margin.application;

import cl.minimarket.pos.reporting.margin.MarginGroupBy;
import cl.minimarket.pos.reporting.margin.MarginRow;
import cl.minimarket.pos.reporting.margin.infrastructure.MarginReportRepository;
import cl.minimarket.pos.reporting.margin.infrastructure.MarginReportRepository.MarginAggregateRow;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Servicio de aplicación para el reporte de margen y utilidad ({@code HU-REP-04}).
 *
 * <p>Calcula para cada grupo la utilidad bruta ({@code netSales − cost}) y el porcentaje de margen
 * ({@code grossMargin / netSales × 100}) a partir del costo snapshot registrado en las líneas de
 * venta, descontando devoluciones y anulaciones.
 */
@Service
public class MarginReportService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_PCT = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final MarginReportRepository repository;
    private final BusinessClock clock;

    public MarginReportService(MarginReportRepository repository, BusinessClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Genera el reporte de margen y utilidad para el filtro y agrupación indicados.
     *
     * @param filter  filtro de fechas obligatorio y locales (si {@code storeIds} viene vacío, usa
     *                el local del {@link StoreContext} activo)
     * @param groupBy criterio de agrupación ({@link MarginGroupBy#PRODUCT} si es {@code null})
     * @return respuesta con filas y fila de totales
     */
    @Transactional(readOnly = true)
    public ReportResponse<MarginRow> generate(ReportFilter filter, MarginGroupBy groupBy) {
        Objects.requireNonNull(filter, "filter");
        filter.requireRange();

        MarginGroupBy effectiveGroupBy = groupBy == null ? MarginGroupBy.PRODUCT : groupBy;
        List<Long> effectiveStoreIds = filter.storeIds().isEmpty()
                ? List.of(StoreContext.require().storeId())
                : filter.storeIds();
        ReportFilter effectiveFilter = new ReportFilter(filter.from(), filter.to(), effectiveStoreIds);

        List<MarginAggregateRow> rawRows = repository.findMarginRows(
                effectiveFilter.from(),
                effectiveFilter.to(),
                effectiveStoreIds,
                effectiveGroupBy);

        List<MarginRow> rows = new ArrayList<>(rawRows.size());
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalNetSales = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (MarginAggregateRow raw : rawRows) {
            BigDecimal qty = Rounding.quantity(raw.qty());
            BigDecimal netSales = Rounding.clp(raw.netSales());
            BigDecimal cost = Rounding.costTotal(raw.cost());
            BigDecimal grossMargin = Rounding.costTotal(netSales.subtract(cost));
            BigDecimal marginPct = computeMarginPct(grossMargin, netSales);

            rows.add(new MarginRow(raw.key(), raw.label(), qty, netSales, cost, grossMargin, marginPct));

            totalQty = totalQty.add(qty);
            totalNetSales = totalNetSales.add(netSales);
            totalCost = totalCost.add(cost);
        }

        BigDecimal normTotalQty = Rounding.quantity(totalQty);
        BigDecimal normTotalNet = Rounding.clp(totalNetSales);
        BigDecimal normTotalCost = Rounding.costTotal(totalCost);
        BigDecimal totalGrossMargin = Rounding.costTotal(normTotalNet.subtract(normTotalCost));
        MarginRow totals = new MarginRow(
                "TOTAL",
                "Total",
                normTotalQty,
                normTotalNet,
                normTotalCost,
                totalGrossMargin,
                computeMarginPct(totalGrossMargin, normTotalNet));

        return new ReportResponse<>(effectiveFilter, clock.now(), rows, totals);
    }

    private static BigDecimal computeMarginPct(BigDecimal grossMargin, BigDecimal netSales) {
        if (netSales.signum() <= 0) {
            return ZERO_PCT;
        }
        return grossMargin.divide(netSales, Rounding.INTERMEDIATE)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
