package cl.minimarket.pos.purchasing.receipt.domain;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Entidad JPA de recepción de mercadería ({@code goods_receipts}, {@code MODELO_DATOS.md} B.4).
 */
@Entity
@Table(name = "goods_receipts")
public class GoodsReceipt extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "number", nullable = false, length = 24, unique = true)
    private String number;

    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private GoodsReceiptStatus status = GoodsReceiptStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "supplier_document_type", nullable = false, length = 20)
    private SupplierDocumentType supplierDocumentType;

    @Column(name = "supplier_document_number", length = 30)
    private String supplierDocumentNumber;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "received_by")
    private Long receivedBy;

    @Column(name = "net_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal netTotal = BigDecimal.ZERO;

    @Column(name = "notes")
    private String notes;

    @OneToMany(mappedBy = "goodsReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<GoodsReceiptLine> lines = new ArrayList<>();

    protected GoodsReceipt() {
    }

    /**
     * Crea una recepción de mercadería en estado {@link GoodsReceiptStatus#DRAFT}.
     */
    public GoodsReceipt(String number,
                        Long purchaseOrderId,
                        Long storeId,
                        Long supplierId,
                        SupplierDocumentType supplierDocumentType,
                        String supplierDocumentNumber,
                        BigDecimal netTotal,
                        String notes) {
        this.number = Objects.requireNonNull(number, "number");
        this.purchaseOrderId = purchaseOrderId;
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.supplierId = Objects.requireNonNull(supplierId, "supplierId");
        this.status = GoodsReceiptStatus.DRAFT;
        this.supplierDocumentType = Objects.requireNonNull(supplierDocumentType, "supplierDocumentType");
        this.supplierDocumentNumber = supplierDocumentNumber;
        this.netTotal = Objects.requireNonNull(netTotal, "netTotal");
        this.notes = notes;
    }

    public Long getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public Long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public void setPurchaseOrderId(Long purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }

    public Long getStoreId() {
        return storeId;
    }

    public Long getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(Long supplierId) {
        this.supplierId = Objects.requireNonNull(supplierId, "supplierId");
    }

    public GoodsReceiptStatus getStatus() {
        return status;
    }

    public SupplierDocumentType getSupplierDocumentType() {
        return supplierDocumentType;
    }

    public void setSupplierDocumentType(SupplierDocumentType supplierDocumentType) {
        this.supplierDocumentType = Objects.requireNonNull(supplierDocumentType, "supplierDocumentType");
    }

    public String getSupplierDocumentNumber() {
        return supplierDocumentNumber;
    }

    public void setSupplierDocumentNumber(String supplierDocumentNumber) {
        this.supplierDocumentNumber = supplierDocumentNumber;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Long getReceivedBy() {
        return receivedBy;
    }

    public BigDecimal getNetTotal() {
        return netTotal;
    }

    public void setNetTotal(BigDecimal netTotal) {
        this.netTotal = Objects.requireNonNull(netTotal, "netTotal");
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<GoodsReceiptLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    /** Agrega una línea asociándola bidireccionalmente a esta recepción. */
    public void addLine(GoodsReceiptLine line) {
        Objects.requireNonNull(line, "line");
        line.attachTo(this);
        this.lines.add(line);
    }

    /** Elimina todas las líneas actuales (solo permitido en estado {@code DRAFT}). */
    public void clearLines() {
        this.lines.clear();
    }

    /** Transiciona la recepción de {@code DRAFT} a {@code CONFIRMED}. */
    public void markConfirmed(Instant receivedAt, Long receivedBy, BigDecimal netTotal) {
        this.status = GoodsReceiptStatus.CONFIRMED;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.receivedBy = Objects.requireNonNull(receivedBy, "receivedBy");
        this.netTotal = Objects.requireNonNull(netTotal, "netTotal");
    }

    /** Transiciona la recepción de {@code DRAFT} a {@code CANCELLED}. */
    public void markCancelled(String cancelReason) {
        this.status = GoodsReceiptStatus.CANCELLED;
        if (cancelReason != null && !cancelReason.isBlank()) {
            this.notes = (this.notes == null || this.notes.isBlank())
                    ? "Cancelada: " + cancelReason.trim()
                    : this.notes + " | Cancelada: " + cancelReason.trim();
        }
    }
}
