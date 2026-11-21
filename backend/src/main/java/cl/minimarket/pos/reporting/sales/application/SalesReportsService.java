package cl.minimarket.pos.reporting.sales.application;

import cl.minimarket.pos.reporting.sales.application.SalesReportsRepository.RawAbcProductRow;
import cl.minimarket.pos.reporting.sales.application.SalesReportsRepository.RawCashierRow;
import cl.minimarket.pos.reporting.sales.application.SalesReportsRepository.RawNoRotationRow;
import cl.minimarket.pos.reporting.sales.application.SalesReportsRepository.RawPaymentMethodRow;
import cl.minimarket.pos.reporting.sales.application.SalesReportsRepository.RawProductRankingRow;
import cl.minimarket.pos.reporting.sales.application.SalesReportsRepository.RawSalesGroupRow;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.AbcView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.CashierSalesView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.NoRotationView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.PaymentMethodView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.ProductRankingView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.SalesReportView;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Casos de uso de consulta para los reportes de venta (T84, {@code API.md} F.2/F.3):
 * ventas agrupadas, por cajero, por medio de pago, ranking de productos, productos sin rotación y
 * clasificación Pareto ABC.
 *
 * <p>Todas las consultas se ejecutan en transacción {@code readOnly} usando {@link BusinessClock}
 * para la marca {@code generatedAt}, los límites horarios y el día de negocio.
 */
@Service
@Transactional(readOnly = true)
public class SalesReportsService {

    /** Límite máximo de filas para {@code /reports/product-ranking} según F.2. */
    public static final int MAX_RANKING_LIMIT = 100;
    /** Límite máximo razonable de días de inactividad en {@code /reports/no-rotation}. */
    public static final int MAX_NO_ROTATION_DAYS = 3650;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ABC_A_LIMIT = BigDecimal.valueOf(80);
    private static final BigDecimal ABC_B_LIMIT = BigDecimal.valueOf(95);

    private final SalesReportsRepository repository;
    private final BusinessClock clock;

    public SalesReportsService(SalesReportsRepository repository, BusinessClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Entrada intermedia para clasificar candidatos Pareto ABC ya filtrados con valor positivo. */
    record AbcCandidate(Long productId, String sku, String name, BigDecimal metricValue) {
    }

    /**
     * Reporte de ventas netas de devoluciones agrupadas por día, hora, día de semana, categoría o
     * local ({@code GET /reports/sales}).
     *
     * @param filter  filtro con rango obligatorio y locales validados
     * @param groupBy criterio de agrupación
     * @return respuesta con filas y fila {@code totals} consolidada
     */
    public ReportResponse<SalesReportView> salesReport(ReportFilter filter, SalesGroupBy groupBy) {
        ReportFilter effective = validateEffectiveFilter(filter);
        Objects.requireNonNull(groupBy, "groupBy");

        List<RawSalesGroupRow> rawRows = repository.findSalesGrouped(
                effective.from(),
                effective.to(),
                effective.storeIds(),
                groupBy,
                clock.zone().getId());

        List<SalesReportView> rows = new ArrayList<>(rawRows.size());
        long rowTransactionsSum = 0L;
        BigDecimal sumGross = BigDecimal.ZERO;
        BigDecimal sumNet = BigDecimal.ZERO;
        BigDecimal sumTax = BigDecimal.ZERO;
        BigDecimal sumDiscount = BigDecimal.ZERO;
        BigDecimal sumReturns = BigDecimal.ZERO;

        for (RawSalesGroupRow raw : rawRows) {
            BigDecimal grossTotal = Rounding.clp(raw.grossTotal());
            BigDecimal taxTotal = Rounding.clp(raw.taxTotal());
            BigDecimal netTotal = Rounding.clp(grossTotal.subtract(taxTotal));
            BigDecimal discountTotal = Rounding.clp(raw.discountTotal());
            BigDecimal returnsTotal = Rounding.clp(raw.returnsTotal());
            BigDecimal avgTicket = averageTicket(grossTotal, raw.transactions());
            String label = groupBy == SalesGroupBy.WEEKDAY ? weekdayLabel(raw.key()) : raw.label();

            rows.add(new SalesReportView(
                    raw.key(),
                    label,
                    raw.transactions(),
                    grossTotal,
                    netTotal,
                    taxTotal,
                    discountTotal,
                    returnsTotal,
                    avgTicket));

            rowTransactionsSum += raw.transactions();
            sumGross = sumGross.add(grossTotal);
            sumNet = sumNet.add(netTotal);
            sumTax = sumTax.add(taxTotal);
            sumDiscount = sumDiscount.add(discountTotal);
            sumReturns = sumReturns.add(returnsTotal);
        }

        // En agrupación por categoría una misma boleta puede tocar varias categorías: el total de
        // transacciones se consulta sin doble conteo para cuadrar con los demás groupBy.
        long totalTransactions = groupBy == SalesGroupBy.CATEGORY
                ? repository.countDistinctNonVoidedSales(effective.from(), effective.to(), effective.storeIds())
                : rowTransactionsSum;

        SalesReportView totals = new SalesReportView(
                "TOTAL",
                "Total",
                totalTransactions,
                Rounding.clp(sumGross),
                Rounding.clp(sumNet),
                Rounding.clp(sumTax),
                Rounding.clp(sumDiscount),
                Rounding.clp(sumReturns),
                averageTicket(sumGross, totalTransactions));

        return new ReportResponse<>(effective, clock.now(), rows, totals);
    }

    /**
     * Reporte de ventas netas, anulaciones, devoluciones y overrides por cajero
     * ({@code GET /reports/sales-by-cashier}).
     *
     * @param filter filtro con rango obligatorio y locales validados
     * @return respuesta con filas por cajero y fila {@code totals}
     */
    public ReportResponse<CashierSalesView> salesByCashier(ReportFilter filter) {
        ReportFilter effective = validateEffectiveFilter(filter);
        OffsetDateTime windowStart = utc(clock.startOfDay(effective.from()));
        OffsetDateTime windowEnd = utc(clock.startOfDay(effective.to().plusDays(1)));

        List<RawCashierRow> rawRows = repository.findSalesByCashier(
                effective.from(),
                effective.to(),
                windowStart,
                windowEnd,
                effective.storeIds());

        List<CashierSalesView> rows = new ArrayList<>(rawRows.size());
        long sumTransactions = 0L;
        BigDecimal sumTotal = BigDecimal.ZERO;
        long sumVoids = 0L;
        long sumReturns = 0L;
        BigDecimal sumDiscount = BigDecimal.ZERO;
        long sumOverrides = 0L;

        for (RawCashierRow raw : rawRows) {
            BigDecimal total = Rounding.clp(raw.total());
            BigDecimal discountTotal = Rounding.clp(raw.discountTotal());
            rows.add(new CashierSalesView(
                    raw.userId(),
                    raw.name(),
                    raw.transactions(),
                    total,
                    averageTicket(total, raw.transactions()),
                    raw.voids(),
                    raw.returns(),
                    discountTotal,
                    raw.overridesCount()));

            sumTransactions += raw.transactions();
            sumTotal = sumTotal.add(total);
            sumVoids += raw.voids();
            sumReturns += raw.returns();
            sumDiscount = sumDiscount.add(discountTotal);
            sumOverrides += raw.overridesCount();
        }

        CashierSalesView totals = new CashierSalesView(
                null,
                "Total",
                sumTransactions,
                Rounding.clp(sumTotal),
                averageTicket(sumTotal, sumTransactions),
                sumVoids,
                sumReturns,
                Rounding.clp(sumDiscount),
                sumOverrides);

        return new ReportResponse<>(effective, clock.now(), rows, totals);
    }

    /**
     * Reporte de ventas netas por medio de pago ({@code GET /reports/sales-by-payment-method}).
     *
     * @param filter filtro con rango obligatorio y locales validados
     * @return respuesta con filas por medio de pago y fila {@code totals}
     */
    public ReportResponse<PaymentMethodView> salesByPaymentMethod(ReportFilter filter) {
        ReportFilter effective = validateEffectiveFilter(filter);
        List<RawPaymentMethodRow> rawRows = repository.findSalesByPaymentMethod(
                effective.from(), effective.to(), effective.storeIds());
        long distinctTransactions = repository.countDistinctSalesWithApprovedPayments(
                effective.from(), effective.to(), effective.storeIds());

        BigDecimal grandTotal = BigDecimal.ZERO;
        for (RawPaymentMethodRow raw : rawRows) {
            grandTotal = grandTotal.add(Rounding.clp(raw.total()));
        }
        grandTotal = Rounding.clp(grandTotal);

        List<PaymentMethodView> rows = new ArrayList<>(rawRows.size());
        for (RawPaymentMethodRow raw : rawRows) {
            BigDecimal rowTotal = Rounding.clp(raw.total());
            BigDecimal pct = percentageOf(rowTotal, grandTotal);
            rows.add(new PaymentMethodView(raw.method(), raw.transactions(), rowTotal, pct));
        }

        BigDecimal totalPct = grandTotal.signum() > 0
                ? Rounding.percentage(HUNDRED)
                : Rounding.percentage(BigDecimal.ZERO);
        PaymentMethodView totals = new PaymentMethodView("TOTAL", distinctTransactions, grandTotal, totalPct);

        return new ReportResponse<>(effective, clock.now(), rows, totals);
    }

    /**
     * Ranking de productos más o menos vendidos en el rango ({@code GET /reports/product-ranking}).
     *
     * @param filter     filtro con rango obligatorio y locales validados
     * @param order      dirección ({@code MOST} o {@code LEAST})
     * @param metric     métrica de ordenamiento ({@code QTY} o {@code AMOUNT})
     * @param limit      cantidad máxima de filas (1..100)
     * @param categoryId categoría opcional (incluye subcategorías)
     * @return respuesta con las filas rankeadas y {@code totals = null}
     */
    public ReportResponse<ProductRankingView> productRanking(
            ReportFilter filter,
            RankingOrder order,
            RankingMetric metric,
            int limit,
            Long categoryId
    ) {
        ReportFilter effective = validateEffectiveFilter(filter);
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(metric, "metric");
        validateRankingParams(limit, categoryId);

        List<RawProductRankingRow> rawRows = repository.findProductRanking(
                effective.from(),
                effective.to(),
                effective.storeIds(),
                order,
                metric,
                limit,
                categoryId);

        List<ProductRankingView> rows = new ArrayList<>(rawRows.size());
        for (int i = 0; i < rawRows.size(); i++) {
            RawProductRankingRow raw = rawRows.get(i);
            rows.add(new ProductRankingView(
                    raw.productId(),
                    raw.sku(),
                    raw.name(),
                    raw.category(),
                    Rounding.quantity(raw.qty()),
                    Rounding.clp(raw.netSales()),
                    raw.transactions(),
                    i + 1));
        }

        return new ReportResponse<>(effective, clock.now(), rows, null);
    }

    /**
     * Productos con existencia positiva y sin ventas netas en los últimos {@code days} días
     * contados hasta {@code filter.to()} ({@code GET /reports/no-rotation}).
     *
     * @param filter      filtro con rango obligatorio y locales validados ({@code to} define la
     *                    fecha de corte)
     * @param days        ventana de días sin venta (por defecto 30)
     * @param includeCost {@code true} si el usuario posee {@code catalog:cost:read}
     * @return respuesta con productos sin rotación y {@code totals = null}
     */
    public ReportResponse<NoRotationView> noRotation(ReportFilter filter, int days, boolean includeCost) {
        ReportFilter effective = validateEffectiveFilter(filter);
        if (days < 1 || days > MAX_NO_ROTATION_DAYS) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El parámetro days debe estar entre 1 y " + MAX_NO_ROTATION_DAYS + ".",
                    Map.of(),
                    List.of(new FieldErrorDetail(
                            "days",
                            "OUT_OF_RANGE",
                            "La ventana de días sin rotación debe estar entre 1 y " + MAX_NO_ROTATION_DAYS + ".")));
        }

        LocalDate windowStart = effective.to().minusDays(days - 1L);
        List<RawNoRotationRow> rawRows = repository.findNoRotation(
                effective.to(), windowStart, effective.storeIds());

        List<NoRotationView> rows = new ArrayList<>(rawRows.size());
        for (RawNoRotationRow raw : rawRows) {
            BigDecimal stock = Rounding.quantity(raw.stockQty());
            BigDecimal valuation = includeCost
                    ? Rounding.costTotal(stock.multiply(raw.avgCost()))
                    : null;
            Long daysWithoutSale = raw.lastSaleDate() != null
                    ? ChronoUnit.DAYS.between(raw.lastSaleDate(), effective.to())
                    : null;
            rows.add(new NoRotationView(
                    raw.productId(),
                    raw.sku(),
                    raw.name(),
                    stock,
                    valuation,
                    raw.lastSaleAt(),
                    daysWithoutSale));
        }

        return new ReportResponse<>(effective, clock.now(), rows, null);
    }

    /**
     * Clasificación Pareto ABC de productos vendidos en el rango según monto neto, cantidad o
     * margen bruto ({@code GET /reports/abc}).
     *
     * @param filter filtro con rango obligatorio y locales validados
     * @param metric métrica de clasificación ({@code AMOUNT}, {@code QTY} o {@code MARGIN})
     * @return respuesta con filas ordenadas por importancia y clasificadas en A/B/C
     */
    public ReportResponse<AbcView> abc(ReportFilter filter, AbcMetric metric) {
        ReportFilter effective = validateEffectiveFilter(filter);
        Objects.requireNonNull(metric, "metric");

        List<RawAbcProductRow> rawRows = repository.findAbcCandidates(
                effective.from(), effective.to(), effective.storeIds());

        List<AbcCandidate> candidates = new ArrayList<>(rawRows.size());
        for (RawAbcProductRow raw : rawRows) {
            BigDecimal value = switch (metric) {
                case AMOUNT -> Rounding.clp(raw.netSales());
                case QTY -> Rounding.quantity(raw.netQty());
                case MARGIN -> Rounding.costTotal(raw.netSales().subtract(raw.netCost()));
            };
            if (value.signum() > 0) {
                candidates.add(new AbcCandidate(raw.productId(), raw.sku(), raw.name(), value));
            }
        }

        candidates.sort(Comparator.comparing(AbcCandidate::metricValue).reversed()
                .thenComparing(AbcCandidate::name)
                .thenComparing(AbcCandidate::productId));

        List<AbcView> rows = classifyAbc(candidates);
        return new ReportResponse<>(effective, clock.now(), rows, null);
    }

    /**
     * Calcula participación, porcentaje acumulado exacto y clase ABC (A hasta 80 %, B hasta 95 %,
     * C el resto, según {@code API.md} F.3) sobre candidatos ya ordenados descendentemente.
     */
    static List<AbcView> classifyAbc(List<AbcCandidate> sortedCandidates) {
        if (sortedCandidates.isEmpty()) {
            return List.of();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (AbcCandidate candidate : sortedCandidates) {
            total = total.add(candidate.metricValue());
        }
        if (total.signum() <= 0) {
            return List.of();
        }

        List<AbcView> result = new ArrayList<>(sortedCandidates.size());
        BigDecimal runningSum = BigDecimal.ZERO;
        for (AbcCandidate candidate : sortedCandidates) {
            runningSum = runningSum.add(candidate.metricValue());
            BigDecimal pct = Rounding.percentage(
                    candidate.metricValue().multiply(HUNDRED).divide(total, Rounding.INTERMEDIATE));
            BigDecimal cumulativePct = Rounding.percentage(
                    runningSum.multiply(HUNDRED).divide(total, Rounding.INTERMEDIATE));
            String abcClass;
            if (cumulativePct.compareTo(ABC_A_LIMIT) <= 0) {
                abcClass = "A";
            } else if (cumulativePct.compareTo(ABC_B_LIMIT) <= 0) {
                abcClass = "B";
            } else {
                abcClass = "C";
            }
            result.add(new AbcView(
                    candidate.productId(),
                    candidate.sku(),
                    candidate.name(),
                    candidate.metricValue(),
                    pct,
                    cumulativePct,
                    abcClass));
        }
        return result;
    }

    private ReportFilter validateEffectiveFilter(ReportFilter filter) {
        Objects.requireNonNull(filter, "filter");
        ReportFilter validated = filter.requireRange();
        if (!validated.storeIds().isEmpty()) {
            return validated;
        }
        Long contextStoreId = StoreContext.current()
                .map(StoreContext::storeId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Debe indicar al menos un local para ejecutar el reporte.",
                        Map.of(),
                        List.of(new FieldErrorDetail(
                                "storeIds",
                                "REQUIRED",
                                "Debe seleccionar al menos un local."))));
        return new ReportFilter(validated.from(), validated.to(), List.of(contextStoreId));
    }

    private static void validateRankingParams(int limit, Long categoryId) {
        if (limit < 1 || limit > MAX_RANKING_LIMIT) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El límite del ranking debe estar entre 1 y " + MAX_RANKING_LIMIT + ".",
                    Map.of(),
                    List.of(new FieldErrorDetail(
                            "limit",
                            "OUT_OF_RANGE",
                            "El límite debe estar entre 1 y " + MAX_RANKING_LIMIT + ".")));
        }
        if (categoryId != null && categoryId <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El identificador de categoría debe ser positivo.",
                    Map.of(),
                    List.of(new FieldErrorDetail(
                            "categoryId",
                            "INVALID_ID",
                            "El identificador de categoría debe ser mayor que cero.")));
        }
    }

    private static BigDecimal averageTicket(BigDecimal totalAmount, long transactions) {
        if (transactions <= 0) {
            return Rounding.clp(BigDecimal.ZERO);
        }
        return Rounding.clp(totalAmount.divide(BigDecimal.valueOf(transactions), Rounding.INTERMEDIATE));
    }

    private static BigDecimal percentageOf(BigDecimal part, BigDecimal total) {
        if (total.signum() <= 0) {
            return Rounding.percentage(BigDecimal.ZERO);
        }
        return Rounding.percentage(part.multiply(HUNDRED).divide(total, Rounding.INTERMEDIATE));
    }

    private static String weekdayLabel(String isoDowKey) {
        return switch (isoDowKey) {
            case "1" -> "Lunes";
            case "2" -> "Martes";
            case "3" -> "Miércoles";
            case "4" -> "Jueves";
            case "5" -> "Viernes";
            case "6" -> "Sábado";
            case "7" -> "Domingo";
            default -> isoDowKey;
        };
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
