package cl.minimarket.pos.reporting.dashboard.application;

import cl.minimarket.pos.reporting.dashboard.application.DashboardView.AlertsView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.HourlySalesView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.PaymentMixView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.TopProductView;
import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository;
import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository.HourlySalesAggregateRow;
import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository.PaymentMixRow;
import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository.StockAlertsRow;
import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository.TopProductRow;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Caso de uso del dashboard ejecutivo del día ({@code HU-REP-01}, {@code API.md} F.2/F.3).
 *
 * <p>Consolida en una transacción de solo lectura las métricas del día solicitado (o de hoy en la
 * zona de negocio según {@link BusinessClock}), la comparación contra el día calendario anterior,
 * la serie densa de 24 horas ({@code 0..23}), el top 10 de productos, el mix por medio de pago y
 * las alertas operativas vigentes del local activo o del conjunto de locales autorizados.
 */
@Service
public class DashboardService {

    private static final int HOURS_PER_DAY = 24;
    private static final int PCT_DISPLAY_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final DashboardJdbcRepository repository;
    private final BusinessClock businessClock;

    public DashboardService(DashboardJdbcRepository repository, BusinessClock businessClock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.businessClock = Objects.requireNonNull(businessClock, "businessClock");
    }

    /**
     * Genera la vista completa del dashboard para {@code requestedDate} (o la fecha de negocio
     * actual si viene nula) sobre {@code activeStoreId} o los {@code storeIds} solicitados.
     *
     * @param requestedDate  fecha de negocio opcional; {@code null} usa {@link BusinessClock#businessDate()}
     * @param storeIds       lista opcional de locales a consolidar; vacía usa {@code activeStoreId}
     * @param activeStoreId  local activo resuelto desde {@code X-Store-Id}
     * @param userId         identificador del usuario autenticado (puede ser {@code null} si {@code allStoreAccess})
     * @param allStoreAccess {@code true} si el usuario posee {@code security:store:all-access}
     * @return resumen del dashboard diario
     */
    @Transactional(readOnly = true)
    public DashboardView getDashboard(
            LocalDate requestedDate,
            List<Long> storeIds,
            Long activeStoreId,
            Long userId,
            boolean allStoreAccess) {
        Objects.requireNonNull(activeStoreId, "activeStoreId");

        List<Long> effectiveStoreIds = resolveEffectiveStoreIds(storeIds, activeStoreId, userId, allStoreAccess);

        LocalDate today = businessClock.businessDate();
        LocalDate targetDate = requestedDate != null ? requestedDate : today;
        LocalDate previousDate = targetDate.minusDays(1);

        List<HourlySalesAggregateRow> hourlyRows = repository.findHourlySalesForDates(
                effectiveStoreIds, targetDate, previousDate, businessClock.zone().getId());

        Map<Integer, HourlySalesAggregateRow> byHour = new LinkedHashMap<>();
        BigDecimal rawTargetTotal = BigDecimal.ZERO;
        BigDecimal rawPreviousTotal = BigDecimal.ZERO;
        long totalTransactions = 0L;

        for (HourlySalesAggregateRow row : hourlyRows) {
            if (targetDate.equals(row.businessDate())) {
                byHour.merge(row.hour(), row, (a, b) -> new HourlySalesAggregateRow(
                        targetDate,
                        a.hour(),
                        a.transactions() + b.transactions(),
                        a.netTotal().add(b.netTotal())));
                rawTargetTotal = rawTargetTotal.add(row.netTotal());
                totalTransactions += row.transactions();
            } else if (previousDate.equals(row.businessDate())) {
                rawPreviousTotal = rawPreviousTotal.add(row.netTotal());
            }
        }

        BigDecimal salesTotal = Rounding.clp(rawTargetTotal.max(BigDecimal.ZERO));
        BigDecimal previousSalesTotal = Rounding.clp(rawPreviousTotal.max(BigDecimal.ZERO));
        BigDecimal avgTicket = totalTransactions > 0
                ? Rounding.clp(salesTotal.divide(
                        BigDecimal.valueOf(totalTransactions), Rounding.CLP_SCALE, RoundingMode.HALF_UP))
                : Rounding.clp(BigDecimal.ZERO);
        BigDecimal vsPreviousDayPct = previousSalesTotal.signum() > 0
                ? salesTotal.subtract(previousSalesTotal)
                        .multiply(HUNDRED)
                        .divide(previousSalesTotal, PCT_DISPLAY_SCALE, RoundingMode.HALF_UP)
                : null;

        List<HourlySalesView> salesByHour = buildDenseHourlySeries(byHour);

        List<TopProductView> topProducts = repository.findTopProducts(effectiveStoreIds, targetDate).stream()
                .map(DashboardService::toTopProductView)
                .toList();

        List<PaymentMixView> paymentMix = repository.findPaymentMix(effectiveStoreIds, targetDate).stream()
                .map(DashboardService::toPaymentMixView)
                .toList();

        AlertsView alerts = loadAlerts(effectiveStoreIds, today);

        return new DashboardView(
                targetDate,
                salesTotal,
                totalTransactions,
                avgTicket,
                vsPreviousDayPct,
                salesByHour,
                topProducts,
                paymentMix,
                alerts);
    }

    /**
     * Valida y resuelve la lista efectiva de locales del dashboard. Si no se indican {@code storeIds},
     * usa únicamente el local activo; si se indican y no coinciden exactamente con el local activo ya
     * validado por {@code StoreContextFilter}, verifica que todos estén activos y asignados al usuario.
     */
    private List<Long> resolveEffectiveStoreIds(
            List<Long> storeIds,
            Long activeStoreId,
            Long userId,
            boolean allStoreAccess) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of(activeStoreId);
        }

        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : storeIds) {
            if (id == null || id <= 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Los identificadores de local deben ser enteros positivos.",
                        Map.of(),
                        List.of(new FieldErrorDetail("storeIds", "VALIDATION_ERROR",
                                "Cada identificador de local debe ser mayor a cero.")));
            }
            unique.add(id);
        }

        List<Long> resolved = List.copyOf(unique);
        if (resolved.size() == 1 && resolved.getFirst().equals(activeStoreId)) {
            return resolved;
        }
        if (!allStoreAccess && userId == null) {
            throw new BusinessException(
                    ErrorCode.STORE_ACCESS_DENIED,
                    "No tiene acceso a los locales solicitados.");
        }
        long accessibleCount = repository.countAccessibleActiveStores(resolved, userId, allStoreAccess);
        if (accessibleCount != resolved.size()) {
            throw new BusinessException(
                    ErrorCode.STORE_ACCESS_DENIED,
                    "No tiene acceso a uno o más locales solicitados.");
        }
        return resolved;
    }

    /** Construye la serie densa de 24 horas ({@code 0..23}) evitando huecos en el gráfico horario. */
    private static List<HourlySalesView> buildDenseHourlySeries(Map<Integer, HourlySalesAggregateRow> byHour) {
        List<HourlySalesView> series = new ArrayList<>(HOURS_PER_DAY);
        BigDecimal zeroClp = Rounding.clp(BigDecimal.ZERO);
        for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
            HourlySalesAggregateRow row = byHour.get(hour);
            if (row == null) {
                series.add(new HourlySalesView(hour, zeroClp, 0L));
            } else {
                series.add(new HourlySalesView(
                        hour,
                        Rounding.clp(row.netTotal().max(BigDecimal.ZERO)),
                        row.transactions()));
            }
        }
        return series;
    }

    private AlertsView loadAlerts(List<Long> storeIds, LocalDate today) {
        StockAlertsRow stockAlerts = repository.findStockAlerts(storeIds);
        long expiringLots = repository.countExpiringLots(storeIds, today);
        long pendingShrinkages = repository.countPendingShrinkages(storeIds);
        long pendingAdjustments = repository.countPendingAdjustments(storeIds);
        long pendingCounts = repository.countPendingCounts(storeIds);
        long pendingCashDifferences = repository.countPendingCashDifferences(storeIds);

        return new AlertsView(
                stockAlerts.belowMin(),
                stockAlerts.zeroStock(),
                expiringLots,
                pendingShrinkages,
                pendingAdjustments,
                pendingCounts,
                pendingCashDifferences);
    }

    private static TopProductView toTopProductView(TopProductRow row) {
        return new TopProductView(
                row.productId(),
                row.name(),
                Rounding.quantity(row.qty()),
                Rounding.clp(row.total().max(BigDecimal.ZERO)));
    }

    private static PaymentMixView toPaymentMixView(PaymentMixRow row) {
        return new PaymentMixView(
                row.method(),
                Rounding.clp(row.total().max(BigDecimal.ZERO)));
    }
}
