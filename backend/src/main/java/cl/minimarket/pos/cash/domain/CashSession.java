package cl.minimarket.pos.cash.domain;

import cl.minimarket.pos.cash.CashSessionStatus;
import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Entidad de turno de caja ({@code cash_sessions}, {@code MODELO_DATOS.md} B.8).
 *
 * <p>Mantiene la invariante RN-10 mediante índices únicos parciales en base de datos: como máximo un
 * turno en estado {@link CashSessionStatus#OPEN} por terminal y por cajero.
 */
@Entity
@Table(name = "cash_sessions")
public class CashSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "number", nullable = false, length = 24, unique = true)
    private String number;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "terminal_id", nullable = false)
    private Long terminalId;

    @Column(name = "cashier_user_id", nullable = false)
    private Long cashierUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private CashSessionStatus status = CashSessionStatus.OPEN;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "opening_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal openingAmount;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "expected_total", precision = 14, scale = 0)
    private BigDecimal expectedTotal;

    @Column(name = "counted_total", precision = 14, scale = 0)
    private BigDecimal countedTotal;

    @Column(name = "difference_total", precision = 14, scale = 0)
    private BigDecimal differenceTotal;

    @Column(name = "difference_approved_by")
    private Long differenceApprovedBy;

    @Column(name = "difference_approved_at")
    private Instant differenceApprovedAt;

    @Column(name = "closing_notes", length = 500)
    private String closingNotes;

    /** Constructor requerido por JPA. */
    public CashSession() {
    }

    public Long getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public Long getStoreId() {
        return storeId;
    }

    public void setStoreId(Long storeId) {
        this.storeId = storeId;
    }

    public Long getTerminalId() {
        return terminalId;
    }

    public void setTerminalId(Long terminalId) {
        this.terminalId = terminalId;
    }

    public Long getCashierUserId() {
        return cashierUserId;
    }

    public void setCashierUserId(Long cashierUserId) {
        this.cashierUserId = cashierUserId;
    }

    public CashSessionStatus getStatus() {
        return status;
    }

    public void setStatus(CashSessionStatus status) {
        this.status = status;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public BigDecimal getOpeningAmount() {
        return openingAmount;
    }

    public void setOpeningAmount(BigDecimal openingAmount) {
        this.openingAmount = openingAmount;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public Long getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(Long closedBy) {
        this.closedBy = closedBy;
    }

    public BigDecimal getExpectedTotal() {
        return expectedTotal;
    }

    public void setExpectedTotal(BigDecimal expectedTotal) {
        this.expectedTotal = expectedTotal;
    }

    public BigDecimal getCountedTotal() {
        return countedTotal;
    }

    public void setCountedTotal(BigDecimal countedTotal) {
        this.countedTotal = countedTotal;
    }

    public BigDecimal getDifferenceTotal() {
        return differenceTotal;
    }

    public void setDifferenceTotal(BigDecimal differenceTotal) {
        this.differenceTotal = differenceTotal;
    }

    public Long getDifferenceApprovedBy() {
        return differenceApprovedBy;
    }

    public void setDifferenceApprovedBy(Long differenceApprovedBy) {
        this.differenceApprovedBy = differenceApprovedBy;
    }

    public Instant getDifferenceApprovedAt() {
        return differenceApprovedAt;
    }

    public void setDifferenceApprovedAt(Instant differenceApprovedAt) {
        this.differenceApprovedAt = differenceApprovedAt;
    }

    public String getClosingNotes() {
        return closingNotes;
    }

    public void setClosingNotes(String closingNotes) {
        this.closingNotes = closingNotes;
    }
}
