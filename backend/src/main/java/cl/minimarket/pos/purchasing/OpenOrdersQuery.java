package cl.minimarket.pos.purchasing;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Consulta pública de órdenes de compra abiertas para cálculo de inventario en tránsito
 * ({@code PLAN.md} A.4, C.1).
 */
public interface OpenOrdersQuery {

    /**
     * Devuelve las cantidades pendientes de recibir ({@code qty_ordered - qty_received}) por
     * producto en el local indicado, considerando únicamente órdenes en estado {@code SENT} o
     * {@code PARTIALLY_RECEIVED}.
     *
     * @param storeId identificador del local
     * @return mapa {@code productId -> cantidad pendiente} (escala 3, solo cantidades &gt; 0)
     */
    Map<Long, BigDecimal> pendingQuantities(Long storeId);
}
