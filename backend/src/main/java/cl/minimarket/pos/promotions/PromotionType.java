package cl.minimarket.pos.promotions;

/**
 * Tipos de promoción soportados por el motor de precios ({@code MODELO_DATOS.md} B.10).
 */
public enum PromotionType {
    /** Descuento porcentual sobre el precio de venta bruto ({@code percent} en {@code (0, 100]}). */
    PERCENT,
    /** Descuento en monto fijo en CLP ({@code amount > 0}). */
    FIXED_AMOUNT,
    /** Lleva X y paga Y unidades ({@code buyQty > payQty >= 1}). */
    BUY_X_PAY_Y,
    /** Precio unitario especial desde N unidades ({@code minQty > 0}, {@code amount > 0}). */
    QUANTITY_PRICE,
    /** Precio cerrado por la combinación de productos ({@code comboPrice > 0}). */
    COMBO
}
