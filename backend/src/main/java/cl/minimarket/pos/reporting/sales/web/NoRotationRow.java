package cl.minimarket.pos.reporting.sales.web;

import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.NoRotationView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Fila del reporte de productos con stock y sin rotación ({@code GET /api/v1/reports/no-rotation},
 * {@code API.md} F.3). {@code valuation} sale {@code null} sin el permiso {@code catalog:cost:read}.
 */
@Schema(name = "NoRotationRow", description = "Producto con existencia disponible y sin ventas en la ventana.")
public record NoRotationRow(
        @Schema(description = "Identificador del producto.")
        Long productId,
        @Schema(description = "SKU del producto.")
        String sku,
        @Schema(description = "Nombre del producto.")
        String name,
        @Schema(description = "Stock disponible agregado en los locales consultados (escala 3).")
        BigDecimal stock,
        @Schema(description = "Valorización a costo promedio (null sin permiso catalog:cost:read).")
        BigDecimal valuation,
        @Schema(description = "Instante UTC de la última venta registrada hasta la fecha de corte, o null si nunca se vendió.")
        Instant lastSaleAt,
        @Schema(description = "Días transcurridos desde la última venta hasta la fecha de corte, o null si nunca se vendió.")
        Long daysWithoutSale
) {

    /** Convierte la proyección de aplicación al DTO HTTP. */
    public static NoRotationRow from(NoRotationView view) {
        return new NoRotationRow(
                view.productId(),
                view.sku(),
                view.name(),
                view.stock(),
                view.valuation(),
                view.lastSaleAt(),
                view.daysWithoutSale());
    }
}
