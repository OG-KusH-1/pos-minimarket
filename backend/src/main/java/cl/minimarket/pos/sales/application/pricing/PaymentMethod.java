package cl.minimarket.pos.sales.application.pricing;

/**
 * Medios de pago admitidos en el POS y en las ventas ({@code MODELO_DATOS.md} B.14 {@code payment_method}).
 */
public enum PaymentMethod {
    /** Efectivo CLP (sujeto a redondeo legal Ley 20.956). */
    CASH,
    /** Tarjeta de débito. */
    DEBIT,
    /** Tarjeta de crédito. */
    CREDIT,
    /** Transferencia electrónica. */
    TRANSFER,
    /** Fiado / cuenta corriente de cliente. */
    CUSTOMER_CREDIT
}
