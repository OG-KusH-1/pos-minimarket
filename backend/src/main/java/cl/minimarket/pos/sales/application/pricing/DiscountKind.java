package cl.minimarket.pos.sales.application.pricing;

/**
 * Tipo de descuento manual aplicable a una línea o al total de una venta
 * ({@code MODELO_DATOS.md} B.14 {@code discount_kind}).
 */
public enum DiscountKind {
    /** Descuento porcentual en (0, 100]. */
    PERCENT,
    /** Descuento fijo en CLP (> 0). */
    AMOUNT
}
