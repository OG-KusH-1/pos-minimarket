package cl.minimarket.pos.catalog.tax.domain;

/**
 * Tipo de impuesto configurable ({@code taxes.tax_type}, MODELO_DATOS B.14).
 */
public enum TaxType {
    /** Impuesto al Valor Agregado (IVA). */
    VAT,
    /** Impuesto adicional específico (ILA, IABA, etc.). */
    ADDITIONAL
}
