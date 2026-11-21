package cl.minimarket.pos.reporting.dashboard.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Vista de aplicación del dashboard diario ({@code HU-REP-01}, {@code API.md} F.3).
 *
 * @param date             fecha de negocio consultada
 * @param salesTotal       total de ventas netas de devoluciones en CLP (escala 0)
 * @param transactions     cantidad de ventas válidas (no anuladas) del día
 * @param avgTicket        ticket promedio {@code salesTotal / transactions} en CLP (escala 0; {@code 0} si no hay ventas)
 * @param vsPreviousDayPct variación porcentual respecto del día anterior (escala 2), o {@code null} si el día anterior tuvo ventas en 0
 * @param salesByHour      serie densa de las 24 horas del día ({@code 0..23}) en zona de negocio
 * @param topProducts      hasta 10 productos más vendidos del día por monto neto
 * @param paymentMix       totales netos por medio de pago con monto positivo
 * @param alerts           contadores de alertas operativas vigentes
 */
public record DashboardView(
        LocalDate date,
        BigDecimal salesTotal,
        long transactions,
        BigDecimal avgTicket,
        BigDecimal vsPreviousDayPct,
        List<HourlySalesView> salesByHour,
        List<TopProductView> topProducts,
        List<PaymentMixView> paymentMix,
        AlertsView alerts
) {

    public DashboardView {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(salesTotal, "salesTotal");
        Objects.requireNonNull(avgTicket, "avgTicket");
        salesByHour = salesByHour == null ? List.of() : List.copyOf(salesByHour);
        topProducts = topProducts == null ? List.of() : List.copyOf(topProducts);
        paymentMix = paymentMix == null ? List.of() : List.copyOf(paymentMix);
        Objects.requireNonNull(alerts, "alerts");
    }

    /** Ventas netas y transacciones de una hora específica ({@code 0..23}). */
    public record HourlySalesView(int hour, BigDecimal total, long transactions) {
    }

    /** Producto destacado dentro del top 10 de ventas del día. */
    public record TopProductView(Long productId, String name, BigDecimal qty, BigDecimal total) {
    }

    /** Total neto recaudado en un medio de pago. */
    public record PaymentMixView(String method, BigDecimal total) {
    }

    /** Resumen de alertas operativas de los locales consultados. */
    public record AlertsView(
            long belowMin,
            long zeroStock,
            long expiringLots,
            long pendingShrinkages,
            long pendingAdjustments,
            long pendingCounts,
            long pendingCashDifferences
    ) {
    }
}
