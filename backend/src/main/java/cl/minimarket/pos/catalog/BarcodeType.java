package cl.minimarket.pos.catalog;

/**
 * Tipo de código de barras asociado a un producto (MODELO_DATOS B.14).
 */
public enum BarcodeType {
    /** Código EAN-13 con dígito verificador GS1. */
    EAN13,
    /** Código EAN-8 con dígito verificador GS1. */
    EAN8,
    /** Código UPC-A de 12 dígitos con dígito verificador. */
    UPC_A,
    /** Código interno alfanumérico del minimarket. */
    INTERNAL,
    /** PLU de balanza (espacio de unicidad independiente). */
    SCALE_PLU
}
