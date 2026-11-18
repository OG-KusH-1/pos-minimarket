package cl.minimarket.pos.inventory;

import java.util.List;
import java.util.Objects;

/**
 * Comando atómico para aplicar variaciones de stock en un local mediante {@link StockApi#apply(StockCommand)}.
 *
 * @param storeId      identificador del local donde se afecta el stock
 * @param movementType tipo de movimiento de kardex (define si suma o resta)
 * @param sourceType   tipo de documento origen
 * @param sourceId     identificador del documento origen
 * @param userId       identificador del usuario responsable del movimiento
 * @param reason       motivo o glosa opcional (máximo 250 caracteres)
 * @param lines        líneas de producto a procesar (no vacía)
 */
public record StockCommand(
        Long storeId,
        StockMovementType movementType,
        StockSourceType sourceType,
        Long sourceId,
        Long userId,
        String reason,
        List<StockLine> lines) {

    public StockCommand {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(movementType, "movementType");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
    }
}
