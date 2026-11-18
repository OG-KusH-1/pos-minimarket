package cl.minimarket.pos.shrinkage;

/**
 * Tipos de merma admitidos en {@code shrinkages.shrinkage_type} ({@code MODELO_DATOS.md} B.6 y B.14).
 */
public enum ShrinkageType {
    /** Producto vencido. */
    EXPIRED,
    /** Producto dañado. */
    DAMAGED,
    /** Producto roto o quebrado. */
    BROKEN,
    /** Robo o extravío. */
    THEFT_LOSS,
    /** Consumo interno. */
    INTERNAL_CONSUMPTION,
    /** Error de recepción. */
    RECEIVING_ERROR,
    /** Diferencia en recepción de transferencia entre locales (solo sistémica). */
    TRANSFER_DIFFERENCE
}
