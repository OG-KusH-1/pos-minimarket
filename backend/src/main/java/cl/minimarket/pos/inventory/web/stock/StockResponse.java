package cl.minimarket.pos.inventory.web.stock;

import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.application.query.StockQueryService.StockItemView;
import cl.minimarket.pos.inventory.application.query.StockStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Vista de stock de un producto en el local activo ({@code API.md} F.3).
 *
 * <p>El campo {@code valuation} se incluye únicamente si el usuario posee {@code catalog:cost:read}.
 */
public record StockResponse(
        Long productId,
        String sku,
        String name,
        String categoryName,
        UnitOfMeasure unitOfMeasure,
        BigDecimal quantity,
        BigDecimal minQty,
        BigDecimal maxQty,
        StockStatus status,
        BigDecimal valuation,
        OffsetDateTime lastMovementAt
) {

    /** Convierte una vista de consulta en DTO de respuesta. */
    public static StockResponse from(StockItemView view) {
        return new StockResponse(
                view.productId(),
                view.sku(),
                view.name(),
                view.categoryName(),
                view.unitOfMeasure(),
                view.quantity(),
                view.minQty(),
                view.maxQty(),
                view.status(),
                view.valuation(),
                view.lastMovementAt());
    }
}
