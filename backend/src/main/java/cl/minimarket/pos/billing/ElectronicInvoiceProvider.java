package cl.minimarket.pos.billing;

/**
 * Puerto de integración con el proveedor de facturación electrónica ({@code PLAN.md} A.4).
 *
 * <p>En el MVP se implementa mediante un adaptador simulado que genera folios, {@code trackId} y
 * timbre ficticio, permitiendo sustituirlo en el futuro por un proveedor SII real sin alterar el
 * dominio de {@code billing}.
 */
public interface ElectronicInvoiceProvider {

    /**
     * Envía un documento tributario para su emisión y timbraje.
     *
     * @param request datos del documento a emitir
     * @return resultado de la emisión
     */
    IssueResult issue(TaxDocumentRequest request);

    /**
     * Consulta el estado de un documento ya enviado al proveedor.
     *
     * @param providerTrackId identificador de seguimiento del proveedor
     * @return estado reportado por el proveedor
     */
    DocumentStatusResult status(String providerTrackId);

    /**
     * Código estable del adaptador de proveedor (p. ej. {@code SIMULATED}).
     *
     * @return código del proveedor
     */
    String providerCode();
}
