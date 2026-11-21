package cl.minimarket.pos.reporting.sales.web;

import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.PaymentMethodView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila del reporte por medio de pago ({@code GET /api/v1/reports/sales-by-payment-method},
 * {@code API.md} F.3).
 */
@Schema(name = "PaymentMethodRow", description = "Distribución de ventas netas por medio de pago.")
public record PaymentMethodRow(
        @Schema(description = "Medio de pago (CASH, DEBIT, CREDIT, TRANSFER, CUSTOMER_CREDIT o TOTAL).")
        String method,
        @Schema(description = "Cantidad de ventas distintas no anuladas con este medio de pago.")
        long transactions,
        @Schema(description = "Monto neto de reembolsos recibido por el medio de pago (CLP).")
        BigDecimal total,
        @Schema(description = "Participación porcentual sobre el total recaudado (escala 4).")
        BigDecimal pct
) {

    /** Convierte la proyección de aplicación al DTO HTTP. */
    public static PaymentMethodRow from(PaymentMethodView view) {
        return new PaymentMethodRow(
                view.method(),
                view.transactions(),
                view.total(),
                view.pct());
    }
}
