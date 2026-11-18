package cl.minimarket.pos.cash;

/**
 * Tipos de movimiento manual de efectivo dentro de un turno de caja ({@code cash_movements.movement_type}).
 */
public enum CashMovementType {
    /** Ingreso de efectivo a la caja (ej. sencillo adicional). */
    CASH_IN,
    /** Retiro de efectivo desde la caja (ej. depósito o pago menor). */
    CASH_OUT
}
