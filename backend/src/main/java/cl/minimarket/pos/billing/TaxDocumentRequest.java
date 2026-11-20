package cl.minimarket.pos.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Solicitud de emisión de un documento tributario electrónico enviada al puerto
 * {@link ElectronicInvoiceProvider} ({@code PLAN.md} A.4).
 *
 * @param documentType tipo interno del documento
 * @param siiTypeCode código SII (39, 41 o 61)
 * @param folio folio correlativo asignado al documento
 * @param issueDate fecha de emisión comercial
 * @param emitter datos del emisor (local / empresa)
 * @param receptor datos del receptor (opcional en boletas)
 * @param lines detalle de líneas
 * @param totals totales tributarios
 * @param referencedDocument documento de referencia (obligatorio en notas de crédito)
 */
public record TaxDocumentRequest(
        TaxDocumentType documentType,
        short siiTypeCode,
        long folio,
        LocalDate issueDate,
        Emitter emitter,
        Receptor receptor,
        List<Line> lines,
        Totals totals,
        ReferencedDocument referencedDocument
) {

    public TaxDocumentRequest {
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(issueDate, "issueDate");
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(totals, "totals");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Datos del local/sucursal emisor. */
    public record Emitter(
            Long storeId,
            String storeCode,
            String storeName,
            String address,
            String commune,
            String city,
            String siiBranchCode
    ) {
    }

    /** Datos del receptor (opcional para boletas). */
    public record Receptor(
            Long customerId
    ) {
    }

    /** Línea del documento tributario. */
    public record Line(
            int lineNumber,
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal unitPriceGross,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal exemptAmount,
            BigDecimal totalAmount
    ) {
    }

    /** Totales tributarios del documento. */
    public record Totals(
            BigDecimal netAmount,
            BigDecimal exemptAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount
    ) {
    }

    /** Referencia al documento original cuando se emite una nota de crédito. */
    public record ReferencedDocument(
            TaxDocumentType documentType,
            short siiTypeCode,
            long folio,
            LocalDate issueDate
    ) {
    }
}
