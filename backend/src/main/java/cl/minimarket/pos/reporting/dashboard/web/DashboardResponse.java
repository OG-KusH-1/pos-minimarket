package cl.minimarket.pos.reporting.dashboard.web;

import cl.minimarket.pos.reporting.dashboard.application.DashboardView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta HTTP del dashboard ejecutivo diario ({@code API.md} F.3 {@code DashboardResponse}).
 *
 * <p>Todos los subtipos declaran {@code @Schema(name = ...)} únicos para evitar colisiones en la
 * especificación OpenAPI generada por springdoc.
 */
@Schema(name = "DashboardResponse", description = "Resumen diario de ventas, mix de pagos, top 10 productos y alertas.")
public record DashboardResponse(
        @Schema(description = "Fecha de negocio consultada.", example = "2026-11-20")
        LocalDate date,
        @Schema(description = "Ventas netas de devoluciones del día en CLP.", example = "125000")
        BigDecimal salesTotal,
        @Schema(description = "Cantidad de transacciones válidas (no anuladas) del día.", example = "18")
        long transactions,
        @Schema(description = "Ticket promedio del día en CLP (0 si no hay ventas).", example = "6944")
        BigDecimal avgTicket,
        @Schema(description = "Variación porcentual respecto del día anterior (null si el día anterior fue 0).", example = "12.50")
        BigDecimal vsPreviousDayPct,
        @Schema(description = "Serie horaria de las 24 horas del día (0..23) en la zona horaria de negocio.")
        List<HourlySales> salesByHour,
        @Schema(description = "Hasta 10 productos con mayor monto neto de venta en el día.")
        List<TopProduct> topProducts,
        @Schema(description = "Totales netos del día agrupados por medio de pago.")
        List<PaymentMix> paymentMix,
        @Schema(description = "Contadores de alertas operativas vigentes.")
        Alerts alerts
) {

    public DashboardResponse {
        salesByHour = salesByHour == null ? List.of() : List.copyOf(salesByHour);
        topProducts = topProducts == null ? List.of() : List.copyOf(topProducts);
        paymentMix = paymentMix == null ? List.of() : List.copyOf(paymentMix);
    }

    /** Proyecta la vista de aplicación al DTO HTTP del dashboard. */
    public static DashboardResponse from(DashboardView view) {
        return new DashboardResponse(
                view.date(),
                view.salesTotal(),
                view.transactions(),
                view.avgTicket(),
                view.vsPreviousDayPct(),
                view.salesByHour().stream()
                        .map(h -> new HourlySales(h.hour(), h.total(), h.transactions()))
                        .toList(),
                view.topProducts().stream()
                        .map(p -> new TopProduct(p.productId(), p.name(), p.qty(), p.total()))
                        .toList(),
                view.paymentMix().stream()
                        .map(m -> new PaymentMix(m.method(), m.total()))
                        .toList(),
                new Alerts(
                        view.alerts().belowMin(),
                        view.alerts().zeroStock(),
                        view.alerts().expiringLots(),
                        view.alerts().pendingShrinkages(),
                        view.alerts().pendingAdjustments(),
                        view.alerts().pendingCounts(),
                        view.alerts().pendingCashDifferences()));
    }

    /** Punto horario del gráfico de ventas por hora del día. */
    @Schema(name = "DashboardHourlySales", description = "Ventas netas y transacciones en una hora del día.")
    public record HourlySales(
            @Schema(description = "Hora del día en zona de negocio (0..23).", example = "10")
            int hour,
            @Schema(description = "Total neto vendido en esa hora en CLP.", example = "45000")
            BigDecimal total,
            @Schema(description = "Cantidad de transacciones en esa hora.", example = "6")
            long transactions
    ) {
    }

    /** Producto dentro del ranking top 10 del dashboard. */
    @Schema(name = "DashboardTopProduct", description = "Producto destacado del top 10 de ventas del día.")
    public record TopProduct(
            @Schema(description = "Identificador del producto.", example = "12")
            Long productId,
            @Schema(description = "Nombre del producto.", example = "Pan Marraqueta 1 kg")
            String name,
            @Schema(description = "Cantidad neta vendida (escala 3).", example = "14.000")
            BigDecimal qty,
            @Schema(description = "Monto neto vendido en CLP.", example = "28000")
            BigDecimal total
    ) {
    }

    /** Participación neta por medio de pago en el día. */
    @Schema(name = "DashboardPaymentMix", description = "Monto neto recaudado por medio de pago.")
    public record PaymentMix(
            @Schema(description = "Código del medio de pago (CASH, DEBIT, CREDIT, TRANSFER, CUSTOMER_CREDIT).", example = "CASH")
            String method,
            @Schema(description = "Monto neto aplicado en CLP.", example = "85000")
            BigDecimal total
    ) {
    }

    /** Contadores consolidados de alertas operativas del dashboard. */
    @Schema(name = "DashboardAlerts", description = "Alertas de inventario y aprobaciones pendientes.")
    public record Alerts(
            @Schema(description = "Productos con stock igual o inferior al mínimo configurado.", example = "4")
            long belowMin,
            @Schema(description = "Productos con stock en cero.", example = "2")
            long zeroStock,
            @Schema(description = "Lotes con saldo positivo vencidos o dentro de la ventana de alerta.", example = "3")
            long expiringLots,
            @Schema(description = "Mermas en estado PENDING_APPROVAL.", example = "1")
            long pendingShrinkages,
            @Schema(description = "Ajustes manuales de stock en estado PENDING_APPROVAL.", example = "1")
            long pendingAdjustments,
            @Schema(description = "Tomas físicas de inventario en estado SUBMITTED esperando aprobación.", example = "1")
            long pendingCounts,
            @Schema(description = "Turnos cerrados con diferencia fuera de tolerancia pendientes de aprobación.", example = "1")
            long pendingCashDifferences
    ) {
    }
}
