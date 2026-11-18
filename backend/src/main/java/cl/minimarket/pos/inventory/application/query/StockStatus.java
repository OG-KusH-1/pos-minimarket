package cl.minimarket.pos.inventory.application.query;

import java.math.BigDecimal;

/**
 * Estado operativo del stock de un producto en un local respecto a sus límites configurados
 * ({@code API.md} F.3).
 */
public enum StockStatus {
    /** Existencia normal dentro de los límites configurados. */
    OK,
    /** Existencia mayor que cero y menor o igual al mínimo configurado. */
    BELOW_MIN,
    /** Existencia exactamente en cero. */
    ZERO,
    /** Existencia estrictamente mayor al máximo configurado. */
    ABOVE_MAX;

    /**
     * Calcula el estado de stock a partir de la cantidad disponible y los límites opcionales.
     *
     * @param quantity existencia actual
     * @param minQty   stock mínimo configurado (puede ser {@code null})
     * @param maxQty   stock máximo configurado (puede ser {@code null})
     * @return estado calculado
     */
    public static StockStatus evaluate(BigDecimal quantity, BigDecimal minQty, BigDecimal maxQty) {
        if (quantity.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO;
        }
        if (minQty != null && quantity.compareTo(minQty) <= 0) {
            return BELOW_MIN;
        }
        if (maxQty != null && quantity.compareTo(maxQty) > 0) {
            return ABOVE_MAX;
        }
        return OK;
    }
}
