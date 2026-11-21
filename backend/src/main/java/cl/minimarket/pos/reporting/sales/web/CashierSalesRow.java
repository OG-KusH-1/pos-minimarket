package cl.minimarket.pos.reporting.sales.web;

import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.CashierSalesView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila del reporte de ventas por cajero ({@code GET /api/v1/reports/sales-by-cashier},
 * {@code API.md} F.3).
 */
@Schema(name = "CashierSalesRow", description = "Desempeño de ventas, devoluciones y overrides por cajero.")
public record CashierSalesRow(
        @Schema(description = "Identificador del usuario cajero (null en la fila de totales).")
        Long userId,
        @Schema(description = "Nombre completo del cajero.")
        String name,
        @Schema(description = "Cantidad de ventas no anuladas del cajero.")
        long transactions,
        @Schema(description = "Total bruto vendido neto de devoluciones y anulaciones (CLP).")
        BigDecimal total,
        @Schema(description = "Ticket promedio por venta no anulada (CLP).")
        BigDecimal avgTicket,
        @Schema(description = "Cantidad de ventas anuladas (VOID).")
        long voids,
        @Schema(description = "Cantidad de devoluciones registradas (RETURN).")
        long returns,
        @Schema(description = "Total de descuentos otorgados (CLP).")
        BigDecimal discountTotal,
        @Schema(description = "Cantidad de autorizaciones de supervisor (GRANTED) solicitadas.")
        long overridesCount
) {

    /** Convierte la proyección de aplicación al DTO HTTP. */
    public static CashierSalesRow from(CashierSalesView view) {
        return new CashierSalesRow(
                view.userId(),
                view.name(),
                view.transactions(),
                view.total(),
                view.avgTicket(),
                view.voids(),
                view.returns(),
                view.discountTotal(),
                view.overridesCount());
    }
}
