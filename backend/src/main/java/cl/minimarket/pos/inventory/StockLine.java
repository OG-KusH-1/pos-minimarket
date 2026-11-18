package cl.minimarket.pos.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Línea individual de un {@link StockCommand} para modificar el stock de un producto.
 *
 * <p>La cantidad siempre debe ser estrictamente positiva; el signo del movimiento lo determina
 * {@link StockCommand#movementType()}. Los productos {@code PACK} deben ser resueltos por el
 * llamador a su producto base antes de invocar {@link StockApi#apply(StockCommand)} (ADR-17).
 *
 * <p>Incluye desde Fase 1 los campos opcionales de lote ({@code lotId}, {@code lotNumber},
 * {@code expiryDate}) según la errata J-09 para no cambiar la firma cuando llegue FEFO en T48.
 *
 * @param productId    identificador del producto (no nulo)
 * @param quantity     cantidad positiva del movimiento (no nula, mayor que 0)
 * @param unitCost     costo unitario neto opcional (si es nulo se usa el {@code avg_cost} vigente)
 * @param sourceLineId identificador opcional de la línea del documento origen
 * @param lotId        identificador opcional de lote existente (Fase 2 / T48)
 * @param lotNumber    número de lote opcional para entradas (Fase 2 / T48)
 * @param expiryDate   fecha de vencimiento opcional del lote (Fase 2 / T48)
 */
public record StockLine(
        Long productId,
        BigDecimal quantity,
        BigDecimal unitCost,
        Long sourceLineId,
        Long lotId,
        String lotNumber,
        LocalDate expiryDate) {

    public StockLine {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");
    }

    /**
     * Crea una línea sin datos de lote ni costo explícito (usa el {@code avg_cost} vigente).
     *
     * @param productId identificador del producto
     * @param quantity  cantidad positiva
     * @return línea de movimiento de stock
     */
    public static StockLine of(Long productId, BigDecimal quantity) {
        return new StockLine(productId, quantity, null, null, null, null, null);
    }

    /**
     * Crea una línea sin datos de lote, especificando costo unitario y línea de origen.
     *
     * @param productId    identificador del producto
     * @param quantity     cantidad positiva
     * @param unitCost     costo unitario opcional (null para usar {@code avg_cost} vigente)
     * @param sourceLineId identificador de la línea origen (puede ser null)
     * @return línea de movimiento de stock
     */
    public static StockLine of(Long productId, BigDecimal quantity, BigDecimal unitCost, Long sourceLineId) {
        return new StockLine(productId, quantity, unitCost, sourceLineId, null, null, null);
    }

    /** Indica si la línea aporta algún dato de lote. */
    public boolean hasLotData() {
        return lotId != null || (lotNumber != null && !lotNumber.isBlank()) || expiryDate != null;
    }
}
