package cl.minimarket.pos.reporting.inventory.application;

import cl.minimarket.pos.reporting.inventory.InventoryValuationGroupBy;
import cl.minimarket.pos.reporting.inventory.InventoryValuationRow;
import cl.minimarket.pos.reporting.inventory.infrastructure.InventoryValuationReportRepository;
import cl.minimarket.pos.reporting.inventory.infrastructure.InventoryValuationReportRepository.ValuationAggregateRow;
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
 * Servicio de aplicación para el reporte de valorización de inventario ({@code HU-REP-07}).
 *
 * <p>Valoriza el stock actual con {@code products.avg_cost} sin exigir rango de fechas
 * ({@link ReportFilter#optionalRange()}).
 */
@Service
public class InventoryValuationReportService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_PCT = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal FULL_PCT = HUNDRED.setScale(2, RoundingMode.HALF_UP);

    private final InventoryValuationReportRepository repository;
    private final BusinessClock clock;

    public InventoryValuationReportService(
            InventoryValuationReportRepository repository,
            BusinessClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Genera el reporte de valorización actual de inventario.
     *
     * @param filter  filtro con rango opcional y locales
     * @param groupBy criterio de agrupación ({@link InventoryValuationGroupBy#CATEGORY} si es nulo)
     * @return respuesta con filas y fila de totales
     */
    @Transactional(readOnly = true)
    public ReportResponse<InventoryValuationRow> generate(
            ReportFilter filter,
            InventoryValuationGroupBy groupBy) {
        Objects.requireNonNull(filter, "filter");
        filter.optionalRange();

        InventoryValuationGroupBy effectiveGroupBy =
                groupBy == null ? InventoryValuationGroupBy.CATEGORY : groupBy;
        List<Long> effectiveStoreIds = filter.storeIds().isEmpty()
                ? List.of(StoreContext.require().storeId())
                : filter.storeIds();
        ReportFilter effectiveFilter = new ReportFilter(filter.from(), filter.to(), effectiveStoreIds);

        List<ValuationAggregateRow> rawRows = repository.findValuationRows(effectiveStoreIds, effectiveGroupBy);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalValuation = BigDecimal.ZERO;
        for (ValuationAggregateRow raw : rawRows) {
            totalQty = totalQty.add(Rounding.quantity(raw.qty()));
            totalValuation = totalValuation.add(Rounding.costTotal(raw.valuation()));
        }
        BigDecimal normTotalQty = Rounding.quantity(totalQty);
        BigDecimal normTotalValuation = Rounding.costTotal(totalValuation);

        List<InventoryValuationRow> rows = new ArrayList<>(rawRows.size());
        for (ValuationAggregateRow raw : rawRows) {
            BigDecimal qty = Rounding.quantity(raw.qty());
            BigDecimal valuation = Rounding.costTotal(raw.valuation());
            BigDecimal pct = computeSharePct(valuation, normTotalValuation);
            rows.add(new InventoryValuationRow(raw.key(), raw.label(), qty, valuation, pct));
        }

        InventoryValuationRow totals = new InventoryValuationRow(
                "TOTAL",
                "Total",
                normTotalQty,
                normTotalValuation,
                normTotalValuation.signum() > 0 ? FULL_PCT : ZERO_PCT);

        return new ReportResponse<>(effectiveFilter, clock.now(), rows, totals);
    }

    private static BigDecimal computeSharePct(BigDecimal part, BigDecimal total) {
        if (total.signum() <= 0) {
            return ZERO_PCT;
        }
        return part.divide(total, Rounding.INTERMEDIATE)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
