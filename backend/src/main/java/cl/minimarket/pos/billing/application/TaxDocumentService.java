package cl.minimarket.pos.billing.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.billing.ElectronicInvoiceProvider;
import cl.minimarket.pos.billing.IssueResult;
import cl.minimarket.pos.billing.TaxDocumentRequest;
import cl.minimarket.pos.billing.TaxDocumentRequest.Emitter;
import cl.minimarket.pos.billing.TaxDocumentRequest.Line;
import cl.minimarket.pos.billing.TaxDocumentRequest.Receptor;
import cl.minimarket.pos.billing.TaxDocumentRequest.ReferencedDocument;
import cl.minimarket.pos.billing.TaxDocumentRequest.Totals;
import cl.minimarket.pos.billing.TaxDocumentStatus;
import cl.minimarket.pos.billing.TaxDocumentType;
import cl.minimarket.pos.billing.domain.TaxDocument;
import cl.minimarket.pos.billing.domain.TaxDocumentRepository;
import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.sales.SaleCompletedEvent;
import cl.minimarket.pos.sales.SaleReturnedEvent;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.sequence.SequenceService;
import cl.minimarket.pos.shared.sequence.SequenceType;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Servicio de aplicación para la emisión y reintento de documentos tributarios electrónicos.
 *
 * <p>Asigna folios correlativos sin huecos mediante {@link SequenceService} (RN-29), invoca el
 * puerto {@link ElectronicInvoiceProvider} y persiste el resultado en {@code tax_documents}.
 */
@Service
public class TaxDocumentService {

    private static final Logger LOG = LoggerFactory.getLogger(TaxDocumentService.class);
    private static final BigDecimal VAT_DIVISOR = new BigDecimal("1.19");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final TaxDocumentRepository repository;
    private final ElectronicInvoiceProvider provider;
    private final SequenceService sequenceService;
    private final StoreApi storeApi;
    private final AuditService auditService;
    private final BusinessClock clock;
    private final JsonMapper jsonMapper;

    public TaxDocumentService(
            TaxDocumentRepository repository,
            ElectronicInvoiceProvider provider,
            SequenceService sequenceService,
            StoreApi storeApi,
            AuditService auditService,
            BusinessClock clock,
            JsonMapper jsonMapper
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.sequenceService = Objects.requireNonNull(sequenceService, "sequenceService");
        this.storeApi = Objects.requireNonNull(storeApi, "storeApi");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    /**
     * Emite la boleta electrónica (39 o 41) correspondiente a una venta completada.
     *
     * <p>Si ya existe un documento asociado a {@code event.saleId()}, lo devuelve sin duplicar.
     */
    @Transactional
    public TaxDocument issueForSale(SaleCompletedEvent event) {
        Objects.requireNonNull(event, "event");
        Optional<TaxDocument> existing = repository.findBySaleId(event.saleId());
        if (existing.isPresent()) {
            return existing.get();
        }

        BigDecimal netAmount = nonNullZero(event.netTotal());
        BigDecimal exemptAmount = nonNullZero(event.exemptTotal());
        BigDecimal taxAmount = nonNullZero(event.taxTotal());
        BigDecimal totalAmount = netAmount.add(exemptAmount).add(taxAmount);

        TaxDocumentType documentType = isPurelyExempt(netAmount, taxAmount, exemptAmount)
                ? TaxDocumentType.BOLETA_EXENTA
                : TaxDocumentType.BOLETA;

        long folio = nextFolio(SequenceType.FOLIO_BOLETA);
        Emitter emitter = resolveEmitter(event.storeId());
        Receptor receptor = event.customerId() != null ? new Receptor(event.customerId()) : null;
        List<Line> lines = event.lines().stream()
                .map(l -> new Line(
                        l.lineNumber(),
                        l.sku(),
                        l.name(),
                        nonNullZero(l.quantity()),
                        nonNullZero(l.unitPriceGross()),
                        nonNullZero(l.lineNet()),
                        nonNullZero(l.lineTax()),
                        nonNullZero(l.lineExempt()),
                        nonNullZero(l.lineTotalGross())
                ))
                .toList();

        TaxDocumentRequest request = new TaxDocumentRequest(
                documentType,
                documentType.siiTypeCode(),
                folio,
                event.businessDate(),
                emitter,
                receptor,
                lines,
                new Totals(netAmount, exemptAmount, taxAmount, totalAmount),
                null
        );

        TaxDocument document = new TaxDocument(
                event.storeId(),
                documentType,
                folio,
                event.saleId(),
                null,
                null,
                provider.providerCode(),
                netAmount,
                exemptAmount,
                taxAmount,
                totalAmount,
                buildInitialPayload(request)
        );

        dispatchToProvider(document, request);
        return repository.save(document);
    }

    /**
     * Emite la nota de crédito electrónica (61) correspondiente a una anulación o devolución.
     *
     * <p>Si ya existe un documento asociado a {@code event.saleReturnId()}, lo devuelve sin duplicar.
     */
    @Transactional
    public TaxDocument issueForSaleReturn(SaleReturnedEvent event) {
        Objects.requireNonNull(event, "event");
        Optional<TaxDocument> existing = repository.findBySaleReturnId(event.saleReturnId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Optional<TaxDocument> originalDoc = repository.findBySaleId(event.saleId());
        BigDecimal totalAmount = nonNullZero(event.refundTotal());
        Totals totals = computeCreditNoteTotals(totalAmount, originalDoc.orElse(null));

        long folio = nextFolio(SequenceType.FOLIO_CREDIT_NOTE);
        Emitter emitter = resolveEmitter(event.storeId());
        ReferencedDocument referenced = originalDoc
                .map(orig -> new ReferencedDocument(
                        orig.getDocumentType(),
                        orig.getSiiTypeCode(),
                        orig.getFolio(),
                        orig.getIssuedAt() != null
                                ? clock.businessDate(orig.getIssuedAt())
                                : event.businessDate()
                ))
                .orElse(null);

        List<Line> lines = buildCreditNoteLines(event, totals);
        TaxDocumentRequest request = new TaxDocumentRequest(
                TaxDocumentType.CREDIT_NOTE,
                TaxDocumentType.CREDIT_NOTE.siiTypeCode(),
                folio,
                event.businessDate(),
                emitter,
                null,
                lines,
                totals,
                referenced
        );

        TaxDocument document = new TaxDocument(
                event.storeId(),
                TaxDocumentType.CREDIT_NOTE,
                folio,
                null,
                event.saleReturnId(),
                originalDoc.map(TaxDocument::getId).orElse(null),
                provider.providerCode(),
                totals.netAmount(),
                totals.exemptAmount(),
                totals.taxAmount(),
                totals.totalAmount(),
                buildInitialPayload(request)
        );

        dispatchToProvider(document, request);
        return repository.save(document);
    }

    /**
     * Reintenta la emisión de un documento tributario en estado {@code ERROR} o {@code REJECTED}.
     *
     * <p>Reutiliza el mismo folio asignado originalmente y registra la acción en {@code audit_log}.
     */
    @Transactional
    public TaxDocument retry(Long id) {
        Long storeId = StoreContext.require().storeId();
        TaxDocument document = repository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Documento tributario no encontrado."));

        if (!document.canRetry()) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden reintentar documentos en estado ERROR o REJECTED.");
        }

        Map<String, Object> oldSnapshot = new LinkedHashMap<>();
        oldSnapshot.put("status", document.getStatus().name());
        oldSnapshot.put("attempts", document.getAttempts());
        oldSnapshot.put("lastError", document.getLastError());

        TaxDocumentRequest request = reconstructRequest(document);
        dispatchToProvider(document, request);
        TaxDocument saved = repository.save(document);

        Map<String, Object> newSnapshot = new LinkedHashMap<>();
        newSnapshot.put("status", saved.getStatus().name());
        newSnapshot.put("attempts", saved.getAttempts());
        newSnapshot.put("providerTrackId", saved.getProviderTrackId());
        newSnapshot.put("lastError", saved.getLastError());

        auditService.record(new AuditEvent(
                "TAX_DOCUMENT_RETRIED",
                "tax_document",
                saved.getId().toString(),
                oldSnapshot,
                newSnapshot,
                null,
                null
        ));

        return saved;
    }

    private void dispatchToProvider(TaxDocument document, TaxDocumentRequest request) {
        try {
            IssueResult result = provider.issue(request);
            if (result.status() == IssueResult.Status.ISSUED) {
                document.markIssued(result.providerTrackId(), result.stampText(), clock.now());
            } else if (result.status() == IssueResult.Status.REJECTED) {
                document.markFailed(TaxDocumentStatus.REJECTED, result.message());
            } else {
                document.markFailed(TaxDocumentStatus.ERROR, result.message());
            }
        } catch (RuntimeException ex) {
            LOG.warn("Error técnico al emitir documento tributario tipo={} folio={}: {}",
                    document.getDocumentType(), document.getFolio(), ex.getMessage());
            document.markFailed(TaxDocumentStatus.ERROR, ex.getMessage());
        }
    }

    private long nextFolio(SequenceType sequenceType) {
        String formatted = sequenceService.next(sequenceType, null);
        int dashIndex = formatted.lastIndexOf('-');
        if (dashIndex < 0 || dashIndex == formatted.length() - 1) {
            throw new IllegalStateException("Formato de secuencia inválido para folio: " + formatted);
        }
        return Long.parseLong(formatted.substring(dashIndex + 1));
    }

    private Emitter resolveEmitter(Long storeId) {
        Optional<StoreInfo> store = storeApi.find(storeId);
        return store.map(s -> new Emitter(
                        s.id(),
                        s.code(),
                        s.name(),
                        s.address(),
                        s.commune(),
                        s.city(),
                        s.siiBranchCode()))
                .orElseGet(() -> new Emitter(storeId, null, "Local " + storeId, null, null, null, null));
    }

    private Map<String, Object> buildInitialPayload(TaxDocumentRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TaxDocument.PAYLOAD_REQUEST, jsonMapper.convertValue(request, MAP_TYPE));
        return payload;
    }

    private TaxDocumentRequest reconstructRequest(TaxDocument document) {
        Map<String, Object> payload = document.getPayload();
        if (payload != null && payload.get(TaxDocument.PAYLOAD_REQUEST) != null) {
            return jsonMapper.convertValue(
                    payload.get(TaxDocument.PAYLOAD_REQUEST),
                    TaxDocumentRequest.class
            );
        }
        LocalDate issueDate = document.getCreatedAt() != null
                ? clock.businessDate(document.getCreatedAt())
                : clock.businessDate();
        return new TaxDocumentRequest(
                document.getDocumentType(),
                document.getSiiTypeCode(),
                document.getFolio(),
                issueDate,
                resolveEmitter(document.getStoreId()),
                null,
                List.of(),
                new Totals(
                        document.getNetAmount(),
                        document.getExemptAmount(),
                        document.getTaxAmount(),
                        document.getTotalAmount()
                ),
                null
        );
    }

    private static Totals computeCreditNoteTotals(BigDecimal refundTotal, TaxDocument originalDoc) {
        if (refundTotal.signum() == 0) {
            return new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (originalDoc != null && originalDoc.getTotalAmount().signum() > 0) {
            if (originalDoc.getDocumentType() == TaxDocumentType.BOLETA_EXENTA
                    || (originalDoc.getTaxAmount().signum() == 0 && originalDoc.getExemptAmount().signum() > 0)) {
                return new Totals(BigDecimal.ZERO, refundTotal, BigDecimal.ZERO, refundTotal);
            }
            if (originalDoc.getExemptAmount().signum() > 0) {
                BigDecimal origTotal = originalDoc.getTotalAmount();
                BigDecimal net = refundTotal.multiply(originalDoc.getNetAmount())
                        .divide(origTotal, 0, RoundingMode.HALF_UP);
                BigDecimal exempt = refundTotal.multiply(originalDoc.getExemptAmount())
                        .divide(origTotal, 0, RoundingMode.HALF_UP);
                BigDecimal tax = refundTotal.subtract(net).subtract(exempt);
                return new Totals(net, exempt, tax, refundTotal);
            }
        }
        BigDecimal net = refundTotal.divide(VAT_DIVISOR, 0, RoundingMode.HALF_UP);
        BigDecimal tax = refundTotal.subtract(net);
        return new Totals(net, BigDecimal.ZERO, tax, refundTotal);
    }

    private static List<Line> buildCreditNoteLines(SaleReturnedEvent event, Totals totals) {
        if (event.lines().isEmpty()) {
            return List.of();
        }
        boolean purelyExempt = totals.netAmount().signum() == 0
                && totals.taxAmount().signum() == 0
                && totals.exemptAmount().signum() > 0;
        int index = 1;
        java.util.ArrayList<Line> result = new java.util.ArrayList<>(event.lines().size());
        for (SaleReturnedEvent.Line l : event.lines()) {
            BigDecimal lineTotal = nonNullZero(l.refundAmount());
            BigDecimal lineNet;
            BigDecimal lineTax;
            BigDecimal lineExempt;
            if (purelyExempt) {
                lineNet = BigDecimal.ZERO;
                lineTax = BigDecimal.ZERO;
                lineExempt = lineTotal;
            } else {
                lineNet = lineTotal.divide(VAT_DIVISOR, 0, RoundingMode.HALF_UP);
                lineTax = lineTotal.subtract(lineNet);
                lineExempt = BigDecimal.ZERO;
            }
            result.add(new Line(
                    index++,
                    l.sku(),
                    l.name(),
                    nonNullZero(l.quantity()),
                    lineTotal,
                    lineNet,
                    lineTax,
                    lineExempt,
                    lineTotal
            ));
        }
        return result;
    }

    private static boolean isPurelyExempt(
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal exemptAmount
    ) {
        return netAmount.signum() == 0 && taxAmount.signum() == 0 && exemptAmount.signum() > 0;
    }

    private static BigDecimal nonNullZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
