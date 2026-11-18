package cl.minimarket.pos.inventory.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Línea de movimiento de stock ya consolidada por producto y normalizada en escalas.
 *
 * @param productId    identificador del producto
 * @param quantity     cantidad total positiva consolidada (escala 3)
 * @param unitCost     costo unitario explícito opcional (escala 4), o {@code null} para usar {@code avg_cost}
 * @param sourceLineId identificador de línea origen si todas las líneas consolidadas coinciden, o {@code null}
 * @param lotLines     desglose consolidado por lote (siempre al menos un elemento)
 */
public record ConsolidatedStockLine(
        Long productId,
        BigDecimal quantity,
        BigDecimal unitCost,
        Long sourceLineId,
        List<ConsolidatedLotLine> lotLines) {

    public ConsolidatedStockLine {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");
        lotLines = lotLines == null ? List.of() : List.copyOf(lotLines);
    }

    /**
     * Constructor de compatibilidad cuando no se especifica desglose por lote.
     */
    public ConsolidatedStockLine(
            Long productId,
            BigDecimal quantity,
            BigDecimal unitCost,
            Long sourceLineId) {
        this(productId, quantity, unitCost, sourceLineId,
                List.of(new ConsolidatedLotLine(null, null, null, quantity, unitCost)));
    }

    /**
     * Indica si alguna de las sub-líneas de este producto especificó datos de lote.
     *
     * @return {@code true} si existe al menos una sub-línea con datos de lote
     */
    public boolean hasLotData() {
        return lotLines.stream().anyMatch(ConsolidatedLotLine::hasLotData);
    }
}
