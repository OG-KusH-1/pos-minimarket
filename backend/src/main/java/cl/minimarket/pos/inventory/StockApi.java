package cl.minimarket.pos.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * API pública del módulo de Inventario para modificar y consultar existencias.
 *
 * <p>Es la <b>única vía autorizada</b> para modificar {@code stock} y escribir en el kardex
 * ({@code stock_movements}), garantizando las invariantes RN-01 (stock nunca negativo), RN-02
 * (un movimiento de kardex por variación de stock con {@code balance_after} en la misma transacción)
 * y RN-03 (consolidación por producto y bloqueo en orden ascendente de {@code product_id} para
 * prevenir deadlocks).
 */
public interface StockApi {

    /**
     * Aplica de forma atómica y concurrente-segura las variaciones de stock de un comando y registra
     * exactamente un movimiento de kardex por cada producto distinto afectado.
     *
     * <p><b>Contrato transaccional:</b> exige una transacción activa del llamador
     * ({@code Propagation.MANDATORY}, aislamiento {@code READ COMMITTED}). Ante cualquier error
     * (stock insuficiente, fila de stock inexistente, validación) lanza {@code BusinessException}
     * provocando el rollback completo de la transacción.
     *
     * <p><b>Algoritmo (ADR-13 / RN-03):</b>
     * <ol>
     *   <li>Valida el comando y sus líneas (cantidades &gt; 0, sin datos de lote en Fase 1).</li>
     *   <li>Consolida las líneas por {@code productId} sumando sus cantidades.</li>
     *   <li>Ordena los productos consolidados por {@code productId} ascendente para asegurar que
     *       todas las transacciones concurrentes adquieran bloqueos de fila en el mismo orden y
     *       eviten deadlocks.</li>
     *   <li>Para cada producto, ejecuta un {@code UPDATE} condicional atómico sobre {@code stock}
     *       ({@code WHERE store_id = :s AND product_id = :p AND quantity >= :q} en salidas) con
     *       {@code RETURNING quantity}. Si una salida actualiza 0 filas, distingue entre fila
     *       inexistente ({@code NOT_FOUND}) y saldo insuficiente ({@code STOCK_INSUFFICIENT}, 422).</li>
     *   <li>Inserta exactamente un registro en {@code stock_movements} con {@code balance_after}
     *       y el costo unitario vigente (o el explícito de entrada).</li>
     * </ol>
     *
     * @param cmd comando de movimiento de stock
     * @return lista de movimientos generados en el orden ascendente de {@code productId}
     */
    List<StockMovementResult> apply(StockCommand cmd);

    /**
     * Consulta la existencia actual (disponible) de un producto en un local específico.
     *
     * @param storeId   identificador del local
     * @param productId identificador del producto
     * @return cantidad disponible (escala 3); {@code 0.000} si no existe fila de stock
     */
    BigDecimal onHand(Long storeId, Long productId);

    /**
     * Consulta la existencia total de un producto sumando todos los locales de la empresa.
     *
     * <p>Nota de fase: en Fase 1 suma únicamente {@code stock.quantity} de todos los locales; en
     * Fase 2 (T51) sumará además el inventario en tránsito de transferencias enviadas.
     *
     * @param productId identificador del producto
     * @return cantidad total en la empresa (escala 3)
     */
    BigDecimal companyQuantity(Long productId);

    /**
     * Consulta las cantidades en tránsito entrante hacia un local desde otros locales.
     *
     * <p>Nota de fase: en Fase 1 devuelve un mapa vacío; será implementado en T51 cuando existan
     * las transferencias entre locales.
     *
     * @param storeId identificador del local destino
     * @return mapa {@code productId -> cantidad en tránsito} (vacío en Fase 1)
     */
    Map<Long, BigDecimal> inTransitInbound(Long storeId);
}
