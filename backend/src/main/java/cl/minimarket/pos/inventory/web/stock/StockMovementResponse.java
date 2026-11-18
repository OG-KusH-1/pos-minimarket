package cl.minimarket.pos.inventory.web.stock;

import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.application.query.KardexQueryService.StockMovementView;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Movimiento del kardex de un producto ({@code API.md} F.3).
 *
 * <p>El campo {@code unitCost} se incluye únicamente si el usuario posee {@code catalog:cost:read}.
 */
public record StockMovementResponse(
        Long id,
        OffsetDateTime occurredAt,
        StockMovementType movementType,
        BigDecimal quantityDelta,
        BigDecimal balanceAfter,
        BigDecimal unitCost,
        String lotNumber,
        StockSourceType sourceType,
        Long sourceId,
        String reason,
        String username
) {

    /** Convierte una vista de movimiento en DTO de respuesta. */
    public static StockMovementResponse from(StockMovementView view) {
        return new StockMovementResponse(
                view.id(),
                view.occurredAt(),
                view.movementType(),
                view.quantityDelta(),
                view.balanceAfter(),
                view.unitCost(),
                view.lotNumber(),
                view.sourceType(),
                view.sourceId(),
                view.reason(),
                view.username());
    }
}
