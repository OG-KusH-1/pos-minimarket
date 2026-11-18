package cl.minimarket.pos.inventory;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Asignación de cantidad sobre un lote específico dentro de un movimiento de stock (errata J-09).
 *
 * <p>En Fase 1 (sin control de lotes FEFO, que se implementa en T48) la lista de asignaciones de
 * {@link StockMovementResult} viaja vacía. Se declara desde ahora para mantener estable la firma de
 * {@link StockApi}.
 *
 * @param lotId    identificador del lote afectado
 * @param quantity cantidad positiva asignada al lote (escala 3)
 */
public record LotAllocation(Long lotId, BigDecimal quantity) {

    public LotAllocation {
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(quantity, "quantity");
    }
}
