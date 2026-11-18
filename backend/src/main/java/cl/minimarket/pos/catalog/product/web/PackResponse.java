package cl.minimarket.pos.catalog.product.web;

import cl.minimarket.pos.catalog.product.application.ProductService;

/**
 * Respuesta de definición de pack ({@code API.md} F.3).
 */
public record PackResponse(
        Long baseProductId,
        String baseSku,
        String baseName,
        int factor
) {
    /** Proyecta desde la vista de detalle de pack. */
    public static PackResponse from(ProductService.PackDetailView view) {
        if (view == null) {
            return null;
        }
        return new PackResponse(view.baseProductId(), view.baseSku(), view.baseName(), view.factor());
    }
}
