package cl.minimarket.pos.catalog.product.web;

import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.product.application.ProductService;

import java.math.BigDecimal;

/**
 * Resumen de producto para listados paginados ({@code API.md} F.3).
 *
 * <p>{@code avgCost} y {@code marginPct} son {@code null} si el usuario no posee
 * {@code catalog:cost:read}.
 */
public record ProductSummary(
        Long id,
        String sku,
        String name,
        String brandName,
        String categoryName,
        SaleType saleType,
        BigDecimal priceGross,
        String primaryBarcode,
        boolean active,
        BigDecimal avgCost,
        BigDecimal marginPct
) {
    /** Proyecta desde la vista resumida del servicio. */
    public static ProductSummary from(ProductService.ProductSummaryView view) {
        return new ProductSummary(
                view.id(),
                view.sku(),
                view.name(),
                view.brandName(),
                view.categoryName(),
                view.saleType(),
                view.priceGross(),
                view.primaryBarcode(),
                view.active(),
                view.avgCost(),
                view.marginPct()
        );
    }
}
