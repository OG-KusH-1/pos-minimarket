package cl.minimarket.pos.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Resultado de un movimiento de kardex generado por {@link StockApi#apply(StockCommand)}.
 *
 * <p>Se genera exactamente un resultado por cada producto distinto presente en el comando
 * (después de consolidar líneas repetidas del mismo producto).
 *
 * @param movementId    identificador del registro creado en {@code stock_movements}
 * @param storeId       identificador del local
 * @param productId     identificador del producto
 * @param quantityDelta variación neta aplicada (positiva en entradas, negativa en salidas)
 * @param balanceAfter  saldo resultante en {@code stock.quantity} tras el movimiento
 * @param unitCost      costo unitario registrado en el movimiento (escala 4)
 * @param allocations   asignaciones por lote (vacía en Fase 1; poblada en T48 según J-09)
 */
public record StockMovementResult(
        Long movementId,
        Long storeId,
        Long productId,
        BigDecimal quantityDelta,
        BigDecimal balanceAfter,
        BigDecimal unitCost,
        List<LotAllocation> allocations) {

    public StockMovementResult {
        Objects.requireNonNull(movementId, "movementId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantityDelta, "quantityDelta");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(unitCost, "unitCost");
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }
}
