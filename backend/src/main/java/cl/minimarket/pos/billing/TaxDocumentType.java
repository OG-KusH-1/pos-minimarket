package cl.minimarket.pos.billing;

/**
 * Tipos de documento tributario electrónico soportados por el módulo {@code billing}
 * ({@code MODELO_DATOS.md} B.9 {@code tax_documents}).
 */
public enum TaxDocumentType {

    /** Boleta electrónica afecta/mixta (código SII 39). */
    BOLETA((short) 39, "Boleta Electrónica"),

    /** Boleta electrónica exenta de IVA (código SII 41). */
    BOLETA_EXENTA((short) 41, "Boleta Exenta Electrónica"),

    /** Nota de crédito electrónica (código SII 61). */
    CREDIT_NOTE((short) 61, "Nota de Crédito Electrónica");

    private final short siiTypeCode;
    private final String label;

    TaxDocumentType(short siiTypeCode, String label) {
        this.siiTypeCode = siiTypeCode;
        this.label = label;
    }

    /** Código SII del tipo de documento (39, 41 o 61). */
    public short siiTypeCode() {
        return siiTypeCode;
    }

    /** Etiqueta legible en español para la UI y comprobantes. */
    public String label() {
        return label;
    }
}
