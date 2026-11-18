package cl.minimarket.pos.catalog;

/**
 * Modalidad de venta de un producto (MODELO_DATOS B.14).
 */
public enum SaleType {
    /** Se vende por unidad entera. */
    UNIT,
    /** Se vende por peso en kilogramos. */
    WEIGHT,
    /** Agrupación de {@code factor} unidades de un producto base {@code UNIT}. */
    PACK
}
