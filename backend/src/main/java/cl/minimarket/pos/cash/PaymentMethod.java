package cl.minimarket.pos.cash;

/**
 * Medios de pago admitidos en ventas, arqueos de caja y abonos ({@code MODELO_DATOS.md} B.8, B.9).
 */
public enum PaymentMethod {
    /** Efectivo en pesos chilenos. */
    CASH,
    /** Tarjeta de débito. */
    DEBIT,
    /** Tarjeta de crédito. */
    CREDIT,
    /** Transferencia electrónica. */
    TRANSFER,
    /** Cuenta corriente de cliente (fiado). */
    CUSTOMER_CREDIT
}
