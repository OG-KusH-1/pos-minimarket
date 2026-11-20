package cl.minimarket.pos.billing;

/**
 * Resultado de la solicitud de emisión al proveedor de facturación electrónica.
 *
 * @param status estado resultante ({@link Status#ISSUED}, {@link Status#REJECTED} o {@link Status#ERROR})
 * @param providerTrackId identificador de seguimiento asignado por el proveedor (nulo si falló)
 * @param stampText representación textual del timbre electrónico generado (nulo si falló)
 * @param errorCode código de error reportado por el proveedor (opcional)
 * @param message mensaje descriptivo del resultado o error
 */
public record IssueResult(
        Status status,
        String providerTrackId,
        String stampText,
        String errorCode,
        String message
) {

    /** Resultado posible del intento de emisión ante el proveedor. */
    public enum Status {
        ISSUED,
        REJECTED,
        ERROR
    }
}
