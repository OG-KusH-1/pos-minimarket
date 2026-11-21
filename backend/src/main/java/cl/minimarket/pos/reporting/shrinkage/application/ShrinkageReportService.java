package cl.minimarket.pos.reporting.shrinkage.application;

import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.reporting.shrinkage.ShrinkageGroupBy;
import cl.minimarket.pos.reporting.shrinkage.ShrinkageReportRow;
import cl.minimarket.pos.reporting.shrinkage.infrastructure.ShrinkageReportRepository;
import cl.minimarket.pos.reporting.shrinkage.infrastructure.ShrinkageReportRepository.ShrinkageAggregateRow;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Servicio de aplicación para los reportes de mermas ({@code HU-MER-04}, {@code HU-MER-05}).
 *
 * <p>Incluye únicamente mermas {@code APPROVED} y calcula {@code pctOfSales = cost / netSales × 100}
 * contra las ventas netas del mismo filtro.
 */
@Service
public class ShrinkageReportService {

    /** Límite por defecto para el ranking de productos con más merma. */
    public static final int DEFAULT_TOP_LIMIT = 10;
    /** Límite máximo admitido en el ranking de productos con más merma. */
    public static final int MAX_TOP_LIMIT = 100;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_PCT = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "EXPIRED", "Vencimiento",
            "DAMAGED", "Producto dañado",
            "BROKEN", "Rotura",
            "THEFT_LOSS", "Hurto / extravío",
            "INTERNAL_CONSUMPTION", "Consumo interno",
            "RECEIVING_ERROR", "Error de recepción",
            "TRANSFER_DIFFERENCE", "Diferencia en transferencia");

    private final ShrinkageReportRepository repository;
    private final BusinessClock clock;

    public ShrinkageReportService(ShrinkageReportRepository repository, BusinessClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Genera el reporte agregado de mermas aprobadas según {@code groupBy}.
     *
     * @param filter  filtro obligatorio de fechas y locales
     * @param groupBy criterio de agrupación ({@link ShrinkageGroupBy#TYPE} si es nulo)
     * @return respuesta con filas y totales
     */
    @Transactional(readOnly = true)
    public ReportResponse<ShrinkageReportRow> generate(ReportFilter filter, ShrinkageGroupBy groupBy) {
        Objects.requireNonNull(filter, "filter");
        filter.requireRange();

        ShrinkageGroupBy effectiveGroupBy = groupBy == null ? ShrinkageGroupBy.TYPE : groupBy;
        List<Long> effectiveStoreIds = resolveStoreIds(filter);
        ReportFilter effectiveFilter = new ReportFilter(filter.from(), filter.to(), effectiveStoreIds);

        Instant fromInstant = clock.startOfDay(effectiveFilter.from());
        Instant toExclusive = clock.startOfDay(effectiveFilter.to().plusDays(1));

        List<ShrinkageAggregateRow> rawRows = repository.findShrinkageRows(
                fromInstant,
                toExclusive,
                effectiveStoreIds,
                effectiveGroupBy,
                clock.zone().getId());
        BigDecimal totalNetSales = Rounding.clp(
                repository.findTotalNetSales(effectiveFilter.from(), effectiveFilter.to(), effectiveStoreIds));

        return buildResponse(effectiveFilter, rawRows, totalNetSales, effectiveGroupBy == ShrinkageGroupBy.TYPE);
    }

    /**
     * Genera el ranking de los productos con mayor costo acumulado de mermas aprobadas.
     *
     * @param filter filtro obligatorio de fechas y locales
     * @param limit  cantidad máxima de productos a retornar ({@code 1..100}; si es nulo, {@value #DEFAULT_TOP_LIMIT})
     * @return respuesta con los productos ordenados por costo descendente
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si {@code limit} está fuera de rango
     */
    @Transactional(readOnly = true)
    public ReportResponse<ShrinkageReportRow> generateTopProducts(ReportFilter filter, Integer limit) {
        Objects.requireNonNull(filter, "filter");
        filter.requireRange();

        int effectiveLimit = limit == null ? DEFAULT_TOP_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_TOP_LIMIT) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El parámetro limit debe estar entre 1 y " + MAX_TOP_LIMIT + ".",
                    Map.of(),
                    List.of(new FieldErrorDetail("limit", "OUT_OF_RANGE",
                            "limit debe estar entre 1 y " + MAX_TOP_LIMIT + ".")));
        }

        List<Long> effectiveStoreIds = resolveStoreIds(filter);
        ReportFilter effectiveFilter = new ReportFilter(filter.from(), filter.to(), effectiveStoreIds);

        Instant fromInstant = clock.startOfDay(effectiveFilter.from());
        Instant toExclusive = clock.startOfDay(effectiveFilter.to().plusDays(1));

        List<ShrinkageAggregateRow> rawRows = repository.findTopProducts(
                fromInstant,
                toExclusive,
                effectiveStoreIds,
                effectiveLimit);
        BigDecimal totalNetSales = Rounding.clp(
                repository.findTotalNetSales(effectiveFilter.from(), effectiveFilter.to(), effectiveStoreIds));

        return buildResponse(effectiveFilter, rawRows, totalNetSales, false);
    }

    private ReportResponse<ShrinkageReportRow> buildResponse(
            ReportFilter effectiveFilter,
            List<ShrinkageAggregateRow> rawRows,
            BigDecimal totalNetSales,
            boolean translateTypeLabels) {
        List<ShrinkageReportRow> rows = new ArrayList<>(rawRows.size());
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        long totalCount = 0L;

        for (ShrinkageAggregateRow raw : rawRows) {
            BigDecimal qty = Rounding.quantity(raw.qty());
            BigDecimal cost = Rounding.costTotal(raw.cost());
            BigDecimal pctOfSales = computePctOfSales(cost, totalNetSales);
            String label = translateTypeLabels ? TYPE_LABELS.getOrDefault(raw.key(), raw.label()) : raw.label();

            rows.add(new ShrinkageReportRow(raw.key(), label, qty, cost, pctOfSales, raw.count()));

            totalQty = totalQty.add(qty);
            totalCost = totalCost.add(cost);
            totalCount += raw.count();
        }

        BigDecimal normTotalQty = Rounding.quantity(totalQty);
        BigDecimal normTotalCost = Rounding.costTotal(totalCost);
        ShrinkageReportRow totals = new ShrinkageReportRow(
                "TOTAL",
                "Total",
                normTotalQty,
                normTotalCost,
                computePctOfSales(normTotalCost, totalNetSales),
                totalCount);

        return new ReportResponse<>(effectiveFilter, clock.now(), rows, totals);
    }

    private static List<Long> resolveStoreIds(ReportFilter filter) {
        return filter.storeIds().isEmpty()
                ? List.of(StoreContext.require().storeId())
                : filter.storeIds();
    }

    private static BigDecimal computePctOfSales(BigDecimal shrinkageCost, BigDecimal netSales) {
        if (netSales.signum() <= 0) {
            return ZERO_PCT;
        }
        return shrinkageCost.divide(netSales, Rounding.INTERMEDIATE)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
