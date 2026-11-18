package cl.minimarket.pos.catalog.scale.domain;

/**
 * Tipo de valor embebido en un código de barras de balanza ({@code MODELO_DATOS} B.3).
 */
public enum EmbeddedType {
    /** El código embebe el precio total en CLP. */
    PRICE,
    /** El código embebe el peso (p. ej. gramos convertidos a kg según {@code valueDecimals}). */
    WEIGHT
}
