package cl.minimarket.pos.purchasing.order.domain;

/**
 * Estados del ciclo de vida de una orden de compra ({@code purchase_orders.status}).
 */
public enum PurchaseOrderStatus {
    /** Borrador editable. */
    DRAFT,
    /** Aprobada y enviada (pendiente de recepción). */
    SENT,
    /** Recepción parcial confirmada. */
    PARTIALLY_RECEIVED,
    /** Recepción total confirmada. */
    RECEIVED,
    /** Cancelada antes de recibir mercadería. */
    CANCELLED
}
