package cl.minimarket.pos.catalog;

/**
 * Unidad de medida de un producto (MODELO_DATOS B.14).
 */
public enum UnitOfMeasure {
    /** Unidad discreta (para {@link SaleType#UNIT} y {@link SaleType#PACK}). */
    UNIT,
    /** Kilogramo (exclusivo de {@link SaleType#WEIGHT}). */
    KG
}
