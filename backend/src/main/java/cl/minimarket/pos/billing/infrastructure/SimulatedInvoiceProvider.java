package cl.minimarket.pos.billing.infrastructure;

import cl.minimarket.pos.billing.DocumentStatusResult;
import cl.minimarket.pos.billing.ElectronicInvoiceProvider;
import cl.minimarket.pos.billing.IssueResult;
import cl.minimarket.pos.billing.TaxDocumentRequest;
import cl.minimarket.pos.billing.TaxDocumentStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Adaptador simulado de {@link ElectronicInvoiceProvider} para el MVP ({@code PLAN.md} A.4).
 *
 * <p>Genera identificadores de seguimiento y un timbre ficticio determinístico/legible sin realizar
 * llamadas externas al SII.
 */
@Component
public class SimulatedInvoiceProvider implements ElectronicInvoiceProvider {

    /** Código estable del proveedor simulado. */
    public static final String PROVIDER_CODE = "SIMULATED";

    @Override
    public IssueResult issue(TaxDocumentRequest request) {
        Objects.requireNonNull(request, "request");
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String trackId = "SIM-" + request.siiTypeCode() + "-" + request.folio() + "-" + shortId;
        String stampText = "TIMBRE-SII-SIM|"
                + request.siiTypeCode() + "|"
                + request.folio() + "|"
                + request.issueDate() + "|"
                + request.totals().totalAmount();
        return new IssueResult(
                IssueResult.Status.ISSUED,
                trackId,
                stampText,
                null,
                "Documento emitido correctamente (simulado)"
        );
    }

    @Override
    public DocumentStatusResult status(String providerTrackId) {
        Objects.requireNonNull(providerTrackId, "providerTrackId");
        return new DocumentStatusResult(
                TaxDocumentStatus.ISSUED,
                providerTrackId,
                "Documento emitido (simulado)"
        );
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }
}
