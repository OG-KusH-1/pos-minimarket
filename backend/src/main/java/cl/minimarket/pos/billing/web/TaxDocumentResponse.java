package cl.minimarket.pos.billing.web;

import cl.minimarket.pos.billing.TaxDocumentStatus;
import cl.minimarket.pos.billing.TaxDocumentType;
import cl.minimarket.pos.billing.domain.TaxDocument;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Representación pública de un documento tributario electrónico ({@code API.md} F.3
 * {@code TaxDocumentResponse}).
 */
@Schema(name = "TaxDocumentResponse")
public record TaxDocumentResponse(
        Long id,
        TaxDocumentType documentType,
        String typeLabel,
        String stampText,
        short siiTypeCode,
        long folio,
        TaxDocumentStatus status,
        Long saleId,
        Long saleReturnId,
        String provider,
        String providerTrackId,
        BigDecimal netAmount,
        BigDecimal exemptAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        int attempts,
        String lastError,
        Instant issuedAt
) {

    /** Construye el DTO de respuesta desde la entidad de dominio {@link TaxDocument}. */
    public static TaxDocumentResponse from(TaxDocument document) {
        return new TaxDocumentResponse(
                document.getId(),
                document.getDocumentType(),
                document.getDocumentType().label(),
                document.stampText(),
                document.getSiiTypeCode(),
                document.getFolio(),
                document.getStatus(),
                document.getSaleId(),
                document.getSaleReturnId(),
                document.getProvider(),
                document.getProviderTrackId(),
                document.getNetAmount(),
                document.getExemptAmount(),
                document.getTaxAmount(),
                document.getTotalAmount(),
                document.getAttempts(),
                document.getLastError(),
                document.getIssuedAt()
        );
    }
}
