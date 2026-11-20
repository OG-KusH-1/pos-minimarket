package cl.minimarket.pos.billing;

/**
 * Estados del ciclo de vida de un documento tributario electrónico ({@code MODELO_DATOS.md} B.9).
 */
public enum TaxDocumentStatus {

    /** Pendiente de envío al proveedor. */
    PENDING,

    /** Emitido y timbrado correctamente. */
    ISSUED,

    /** Rechazado por el proveedor o por el SII. */
    REJECTED,

    /** Error técnico o de comunicación al emitir; elegible para reintento. */
    ERROR
}
