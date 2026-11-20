package cl.minimarket.pos.customers.domain;

/**
 * Tipos de movimiento en la cuenta corriente de clientes ({@code customer_account_movements}).
 */
public enum CustomerMovementType {
    /** Cargo por venta a fiado (monto positivo, aumenta la deuda). */
    CHARGE,
    /** Abono o pago del cliente (monto negativo, disminuye la deuda). */
    PAYMENT,
    /** Reverso por anulación o devolución de venta (monto negativo, disminuye la deuda). */
    REVERSAL,
    /** Ajuste manual de cuenta corriente (positivo o negativo, distinto de cero). */
    ADJUSTMENT
}
