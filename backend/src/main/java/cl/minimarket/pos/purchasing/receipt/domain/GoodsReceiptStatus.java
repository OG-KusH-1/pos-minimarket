package cl.minimarket.pos.purchasing.receipt.domain;

/**
 * Estados del ciclo de vida de una recepción de mercadería ({@code goods_receipts.status}).
 */
public enum GoodsReceiptStatus {
    /** Borrador editable o cancelable. */
    DRAFT,
    /** Recepción confirmada (stock, lotes y costo promedio actualizados). */
    CONFIRMED,
    /** Recepción cancelada antes de confirmar. */
    CANCELLED
}
