package cl.minimarket.pos.billing.application;

import cl.minimarket.pos.billing.TaxDocumentStatus;
import cl.minimarket.pos.billing.TaxDocumentType;

import java.time.LocalDate;

/**
 * Filtros de búsqueda paginada de documentos tributarios ({@code API.md} F.2).
 *
 * @param status estado del documento (opcional)
 * @param documentType tipo de documento (opcional)
 * @param from fecha comercial inicial inclusive (opcional)
 * @param to fecha comercial final inclusive (opcional)
 * @param folio número de folio exacto (opcional)
 */
public record TaxDocumentFilter(
        TaxDocumentStatus status,
        TaxDocumentType documentType,
        LocalDate from,
        LocalDate to,
        Long folio
) {
}
