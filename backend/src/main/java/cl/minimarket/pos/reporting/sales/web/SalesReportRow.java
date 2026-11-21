package cl.minimarket.pos.reporting.sales.web;

import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.SalesReportView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila del reporte de ventas agrupadas ({@code GET /api/v1/reports/sales}, {@code API.md} F.3).
 */
@Schema(name = "SalesReportRow", description = "Fila del reporte de ventas netas de devoluciones.")
public record SalesReportRow(
        @Schema(description = "Clave de agrupación (fecha ISO, hora, día ISO, id de categoría/local o TOTAL).")
        String key,
        @Schema(description = "Etiqueta legible del grupo.")
        String label,
        @Schema(description = "Cantidad de ventas no anuladas en el grupo.")
        long transactions,
        @Schema(description = "Total bruto vendido neto de devoluciones (CLP).")
        BigDecimal grossTotal,
        @Schema(description = "Total neto sin impuestos, neto de devoluciones (CLP).")
        BigDecimal netTotal,
        @Schema(description = "Total de impuestos, neto de devoluciones (CLP).")
        BigDecimal taxTotal,
        @Schema(description = "Total de descuentos aplicados (CLP).")
        BigDecimal discountTotal,
        @Schema(description = "Total bruto de devoluciones y anulaciones descontadas (CLP).")
        BigDecimal returnsTotal,
        @Schema(description = "Ticket promedio bruto neto de devoluciones por transacción (CLP).")
        BigDecimal avgTicket
) {

    /** Convierte la proyección de aplicación al DTO HTTP. */
    public static SalesReportRow from(SalesReportView view) {
        return new SalesReportRow(
                view.key(),
                view.label(),
                view.transactions(),
                view.grossTotal(),
                view.netTotal(),
                view.taxTotal(),
                view.discountTotal(),
                view.returnsTotal(),
                view.avgTicket());
    }
}
