package cl.minimarket.pos.billing;

/**
 * Resultado de la consulta de estado de un documento previamente enviado al proveedor.
 *
 * @param status estado actual del documento en el proveedor
 * @param providerTrackId identificador de seguimiento consultado
 * @param message mensaje informativo del proveedor
 */
public record DocumentStatusResult(
        TaxDocumentStatus status,
        String providerTrackId,
        String message
) {
}
