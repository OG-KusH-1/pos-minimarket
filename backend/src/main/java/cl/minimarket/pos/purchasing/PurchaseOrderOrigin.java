package cl.minimarket.pos.purchasing;

/**
 * Origen de creación de una orden de compra ({@code purchase_orders.origin}).
 *
 * <p>Vive en el paquete raíz del módulo porque forma parte de la API pública
 * ({@link PurchaseOrderApi#createDrafts}): los demás módulos (p. ej. {@code replenishment})
 * solo pueden importar el paquete raíz de {@code purchasing}, siguiendo el precedente de
 * {@code catalog.SaleType}.
 */
public enum PurchaseOrderOrigin {
    /** Creada manualmente desde el módulo de compras. */
    MANUAL,
    /** Generada automáticamente desde una corrida de reabastecimiento. */
    REPLENISHMENT
}
