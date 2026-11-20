package cl.minimarket.pos.purchasing.order.domain;

import cl.minimarket.pos.purchasing.PurchaseOrderOrigin;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Entidad JPA de orden de compra ({@code purchase_orders}, {@code MODELO_DATOS.md} B.4).
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "number", nullable = false, length = 24, unique = true)
    private String number;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 15)
    private PurchaseOrderOrigin origin = PurchaseOrderOrigin.MANUAL;

    @Column(name = "replenishment_run_id")
    private Long replenishmentRunId;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "sent_by")
    private Long sentBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 250)
    private String cancelReason;

    @Column(name = "notes")
    private String notes;

    @Column(name = "net_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal netTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "total", nullable = false, precision = 14, scale = 0)
    private BigDecimal total = BigDecimal.ZERO;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    /**
     * Crea una orden de compra en estado {@link PurchaseOrderStatus#DRAFT}.
     */
    public PurchaseOrder(String number,
                         Long storeId,
                         Long supplierId,
                         PurchaseOrderOrigin origin,
                         Long replenishmentRunId,
                         LocalDate expectedDate,
                         String notes) {
        this.number = Objects.requireNonNull(number, "number");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.supplierId = Objects.requireNonNull(supplierId, "supplierId");
        this.status = PurchaseOrderStatus.DRAFT;
        this.origin = Objects.requireNonNull(origin, "origin");
        this.replenishmentRunId = replenishmentRunId;
        this.expectedDate = expectedDate;
        this.notes = notes;
    }

    public Long getId() {
        return id;
    }

    public String getNumber() {
        return number;
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

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public PurchaseOrderOrigin getOrigin() {
        return origin;
    }

    public Long getReplenishmentRunId() {
        return replenishmentRunId;
    }

    public LocalDate getExpectedDate() {
        return expectedDate;
    }

    public void setExpectedDate(LocalDate expectedDate) {
        this.expectedDate = expectedDate;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Long getSentBy() {
        return sentBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public BigDecimal getNetTotal() {
        return netTotal;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public List<PurchaseOrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    /** Agrega una línea asociándola bidireccionalmente a esta orden. */
    public void addLine(PurchaseOrderLine line) {
        Objects.requireNonNull(line, "line");
        line.attachTo(this);
        this.lines.add(line);
    }

    /** Elimina todas las líneas actuales (solo permitido en estado {@code DRAFT}). */
    public void clearLines() {
        this.lines.clear();
    }

    /** Actualiza los totales calculados del documento. */
    public void applyTotals(BigDecimal netTotal, BigDecimal taxTotal, BigDecimal total) {
        this.netTotal = Objects.requireNonNull(netTotal, "netTotal");
        this.taxTotal = Objects.requireNonNull(taxTotal, "taxTotal");
        this.total = Objects.requireNonNull(total, "total");
    }

    /** Transiciona la orden de {@code DRAFT} a {@code SENT}. */
    public void markSent(Instant sentAt, Long sentBy) {
        this.status = PurchaseOrderStatus.SENT;
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
        this.sentBy = sentBy;
    }

    /** Transiciona la orden a {@code CANCELLED}. */
    public void markCancelled(Instant cancelledAt, String reason) {
        this.status = PurchaseOrderStatus.CANCELLED;
        this.cancelledAt = Objects.requireNonNull(cancelledAt, "cancelledAt");
        this.cancelReason = Objects.requireNonNull(reason, "reason");
    }
}
