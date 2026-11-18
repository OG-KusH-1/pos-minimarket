package cl.minimarket.pos.sales.application.sale;

/**
 * Estado de un pago registrado en una venta ({@code sale_payments.status},
 * {@code MODELO_DATOS.md} B.9).
 */
public enum SalePaymentStatus {
    /** Pago aprobado y aplicado a la venta. */
    APPROVED,
    /** Pago reversado por anulación (T71). */
    REVERSED
}
