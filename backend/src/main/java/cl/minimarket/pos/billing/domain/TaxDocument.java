package cl.minimarket.pos.billing.domain;

import cl.minimarket.pos.billing.TaxDocumentStatus;
import cl.minimarket.pos.billing.TaxDocumentType;
import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Entidad JPA de la tabla {@code tax_documents} ({@code MODELO_DATOS.md} B.9).
 *
 * <p>Representa un documento tributario electrónico (boleta, boleta exenta o nota de crédito)
 * emitido de forma desacoplada a partir de una venta o de una devolución/anulación.
 */
@Entity
@Table(name = "tax_documents")
public class TaxDocument extends AuditableEntity {

    /** Clave dentro de {@code payload} donde se almacena el timbre generado al emitir. */
    public static final String PAYLOAD_STAMP_TEXT = "stampText";

    /** Clave dentro de {@code payload} con el snapshot serializado del {@code TaxDocumentRequest}. */
    public static final String PAYLOAD_REQUEST = "request";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private TaxDocumentType documentType;

    @Column(name = "sii_type_code", nullable = false)
    private short siiTypeCode;

    @Column(name = "folio", nullable = false)
    private long folio;

    @Column(name = "sale_id")
    private Long saleId;

    @Column(name = "sale_return_id")
    private Long saleReturnId;

    @Column(name = "referenced_document_id")
    private Long referencedDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private TaxDocumentStatus status = TaxDocumentStatus.PENDING;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_track_id", length = 60)
    private String providerTrackId;

    @Column(name = "net_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal netAmount;

    @Column(name = "exempt_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal exemptAmount;

    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "issued_at")
    private Instant issuedAt;

    protected TaxDocument() {
    }

    /**
     * Crea un documento tributario en estado inicial {@link TaxDocumentStatus#PENDING}.
     */
    public TaxDocument(
            Long storeId,
            TaxDocumentType documentType,
            long folio,
            Long saleId,
            Long saleReturnId,
            Long referencedDocumentId,
            String provider,
            BigDecimal netAmount,
            BigDecimal exemptAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            Map<String, Object> payload
    ) {
        if ((saleId == null) == (saleReturnId == null)) {
            throw new IllegalArgumentException(
                    "El documento debe tener exactamente un origen (saleId o saleReturnId).");
        }
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.siiTypeCode = documentType.siiTypeCode();
        this.folio = folio;
        this.saleId = saleId;
        this.saleReturnId = saleReturnId;
        this.referencedDocumentId = referencedDocumentId;
        this.status = TaxDocumentStatus.PENDING;
        this.provider = Objects.requireNonNull(provider, "provider");
        this.netAmount = Objects.requireNonNull(netAmount, "netAmount");
        this.exemptAmount = Objects.requireNonNull(exemptAmount, "exemptAmount");
        this.taxAmount = Objects.requireNonNull(taxAmount, "taxAmount");
        this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount");
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        this.attempts = 0;
    }

    /**
     * Registra un intento exitoso de emisión, guardando trackId, timbre y fecha de emisión.
     */
    public void markIssued(String trackId, String stampText, Instant issuedAt) {
        this.attempts += 1;
        this.status = TaxDocumentStatus.ISSUED;
        this.providerTrackId = trackId;
        this.lastError = null;
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        Map<String, Object> updatedPayload = this.payload == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(this.payload);
        if (stampText != null) {
            updatedPayload.put(PAYLOAD_STAMP_TEXT, stampText);
        }
        this.payload = updatedPayload;
    }

    /**
     * Registra un intento fallido de emisión ({@link TaxDocumentStatus#ERROR} o
     * {@link TaxDocumentStatus#REJECTED}).
     */
    public void markFailed(TaxDocumentStatus failureStatus, String errorMessage) {
        if (failureStatus != TaxDocumentStatus.ERROR && failureStatus != TaxDocumentStatus.REJECTED) {
            throw new IllegalArgumentException("Estado de fallo inválido: " + failureStatus);
        }
        this.attempts += 1;
        this.status = failureStatus;
        this.lastError = errorMessage;
    }

    /** Indica si el documento es elegible para reintento manual. */
    public boolean canRetry() {
        return this.status == TaxDocumentStatus.ERROR || this.status == TaxDocumentStatus.REJECTED;
    }

    /** Devuelve el texto de timbre almacenado en {@code payload}, o {@code null} si aún no existe. */
    public String stampText() {
        if (payload == null) {
            return null;
        }
        Object value = payload.get(PAYLOAD_STAMP_TEXT);
        return value != null ? value.toString() : null;
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public TaxDocumentType getDocumentType() {
        return documentType;
    }

    public short getSiiTypeCode() {
        return siiTypeCode;
    }

    public long getFolio() {
        return folio;
    }

    public Long getSaleId() {
        return saleId;
    }

    public Long getSaleReturnId() {
        return saleReturnId;
    }

    public Long getReferencedDocumentId() {
        return referencedDocumentId;
    }

    public TaxDocumentStatus getStatus() {
        return status;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderTrackId() {
        return providerTrackId;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getExemptAmount() {
        return exemptAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
