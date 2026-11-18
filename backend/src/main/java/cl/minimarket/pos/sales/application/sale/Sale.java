package cl.minimarket.pos.sales.application.sale;

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
import java.util.UUID;

/**
 * Cabecera de venta emitida en el POS ({@code sales}, {@code MODELO_DATOS.md} B.9).
 */
@Entity
@Table(name = "sales")
public class Sale extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "number", nullable = false, length = 24, unique = true)
    private String number;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "terminal_id", nullable = false)
    private Long terminalId;

    @Column(name = "cash_session_id", nullable = false)
    private Long cashSessionId;

    @Column(name = "cashier_user_id", nullable = false)
    private Long cashierUserId;

    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SaleStatus status = SaleStatus.COMPLETED;

    @Column(name = "sold_at", nullable = false)
    private Instant soldAt;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "subtotal_gross", nullable = false, precision = 14, scale = 0)
    private BigDecimal subtotalGross;

    @Column(name = "discount_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "total_gross", nullable = false, precision = 14, scale = 0)
    private BigDecimal totalGross;

    @Column(name = "net_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal netTotal;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal taxTotal;

    @Column(name = "exempt_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal exemptTotal = BigDecimal.ZERO;

    @Column(name = "rounding_adjustment", nullable = false, precision = 4, scale = 0)
    private BigDecimal roundingAdjustment = BigDecimal.ZERO;

    @Column(name = "total_payable", nullable = false, precision = 14, scale = 0)
    private BigDecimal totalPayable;

    @Column(name = "paid_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal paidTotal;

    @Column(name = "change_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal changeTotal = BigDecimal.ZERO;

    @Column(name = "age_confirmed", nullable = false)
    private boolean ageConfirmed;

    @Column(name = "email_to", length = 254)
    private String emailTo;

    protected Sale() {
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

    public Long getCashSessionId() {
        return cashSessionId;
    }

    public void setCashSessionId(Long cashSessionId) {
        this.cashSessionId = cashSessionId;
    }

    public Long getCashierUserId() {
        return cashierUserId;
    }

    public void setCashierUserId(Long cashierUserId) {
        this.cashierUserId = cashierUserId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public SaleStatus getStatus() {
        return status;
    }

    public void setStatus(SaleStatus status) {
        this.status = status;
    }

    public Instant getSoldAt() {
        return soldAt;
    }

    public void setSoldAt(Instant soldAt) {
        this.soldAt = soldAt;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(UUID idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public BigDecimal getSubtotalGross() {
        return subtotalGross;
    }

    public void setSubtotalGross(BigDecimal subtotalGross) {
        this.subtotalGross = subtotalGross;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public void setDiscountTotal(BigDecimal discountTotal) {
        this.discountTotal = discountTotal;
    }

    public BigDecimal getTotalGross() {
        return totalGross;
    }

    public void setTotalGross(BigDecimal totalGross) {
        this.totalGross = totalGross;
    }

    public BigDecimal getNetTotal() {
        return netTotal;
    }

    public void setNetTotal(BigDecimal netTotal) {
        this.netTotal = netTotal;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public void setTaxTotal(BigDecimal taxTotal) {
        this.taxTotal = taxTotal;
    }

    public BigDecimal getExemptTotal() {
        return exemptTotal;
    }

    public void setExemptTotal(BigDecimal exemptTotal) {
        this.exemptTotal = exemptTotal;
    }

    public BigDecimal getRoundingAdjustment() {
        return roundingAdjustment;
    }

    public void setRoundingAdjustment(BigDecimal roundingAdjustment) {
        this.roundingAdjustment = roundingAdjustment;
    }

    public BigDecimal getTotalPayable() {
        return totalPayable;
    }

    public void setTotalPayable(BigDecimal totalPayable) {
        this.totalPayable = totalPayable;
    }

    public BigDecimal getPaidTotal() {
        return paidTotal;
    }

    public void setPaidTotal(BigDecimal paidTotal) {
        this.paidTotal = paidTotal;
    }

    public BigDecimal getChangeTotal() {
        return changeTotal;
    }

    public void setChangeTotal(BigDecimal changeTotal) {
        this.changeTotal = changeTotal;
    }

    public boolean isAgeConfirmed() {
        return ageConfirmed;
    }

    public void setAgeConfirmed(boolean ageConfirmed) {
        this.ageConfirmed = ageConfirmed;
    }

    public String getEmailTo() {
        return emailTo;
    }

    public void setEmailTo(String emailTo) {
        this.emailTo = emailTo;
    }
}
