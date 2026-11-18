package cl.minimarket.pos.purchasing.order.domain;

/**
 * Origen de creación de una orden de compra ({@code purchase_orders.origin}).
 */
public enum PurchaseOrderOrigin {
    /** Creada manualmente desde el módulo de compras. */
    MANUAL,
    /** Generada automáticamente desde una corrida de reabastecimiento. */
    REPLENISHMENT
}
