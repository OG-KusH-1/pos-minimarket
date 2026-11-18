package cl.minimarket.pos.dataexchange.domain;

/**
 * Tipos de importación masiva soportados por {@code dataexchange} ({@code MODELO_DATOS} B.1).
 */
public enum ImportType {
    /** Alta masiva de productos del catálogo. */
    PRODUCTS,
    /** Asociación masiva de productos con proveedores y costos de compra. */
    PRODUCT_SUPPLIERS,
    /** Carga inicial de existencias por local. */
    INITIAL_STOCK
}
