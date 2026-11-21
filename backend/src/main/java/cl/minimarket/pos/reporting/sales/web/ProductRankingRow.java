package cl.minimarket.pos.reporting.sales.web;

import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.ProductRankingView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila del ranking de productos más y menos vendidos
 * ({@code GET /api/v1/reports/product-ranking}, {@code API.md} F.3).
 */
@Schema(name = "ProductRankingRow", description = "Posición de un producto en el ranking de ventas.")
public record ProductRankingRow(
        @Schema(description = "Identificador del producto.")
        Long productId,
        @Schema(description = "SKU del producto.")
        String sku,
        @Schema(description = "Nombre del producto.")
        String name,
        @Schema(description = "Nombre de la categoría del producto.")
        String category,
        @Schema(description = "Cantidad vendida neta de devoluciones (escala 3).")
        BigDecimal qty,
        @Schema(description = "Venta neta sin impuestos, neta de devoluciones (CLP).")
        BigDecimal netSales,
        @Schema(description = "Cantidad de ventas no anuladas que incluyeron el producto.")
        long transactions,
        @Schema(description = "Posición en el ranking (1 = primero).")
        int rank
) {

    /** Convierte la proyección de aplicación al DTO HTTP. */
    public static ProductRankingRow from(ProductRankingView view) {
        return new ProductRankingRow(
                view.productId(),
                view.sku(),
                view.name(),
                view.category(),
                view.qty(),
                view.netSales(),
                view.transactions(),
                view.rank());
    }
}
