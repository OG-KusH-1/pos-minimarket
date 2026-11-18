package cl.minimarket.pos.cash.domain;

import cl.minimarket.pos.cash.PaymentMethod;
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

/**
 * Arqueo por medio de pago registrado al cerrar un turno ({@code cash_session_counts}).
 */
@Entity
@Table(name = "cash_session_counts")
public class CashSessionCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cash_session_id", nullable = false)
    private Long cashSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "expected_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal expectedAmount;

    @Column(name = "counted_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal countedAmount;

    @Column(name = "difference", nullable = false, precision = 14, scale = 0)
    private BigDecimal difference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "denomination_detail")
    private String denominationDetail;

    @Column(name = "notes", length = 250)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Constructor requerido por JPA. */
    public CashSessionCount() {
    }

    public Long getId() {
        return id;
    }

    public Long getCashSessionId() {
        return cashSessionId;
    }

    public void setCashSessionId(Long cashSessionId) {
        this.cashSessionId = cashSessionId;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public void setExpectedAmount(BigDecimal expectedAmount) {
        this.expectedAmount = expectedAmount;
    }

    public BigDecimal getCountedAmount() {
        return countedAmount;
    }

    public void setCountedAmount(BigDecimal countedAmount) {
        this.countedAmount = countedAmount;
    }

    public BigDecimal getDifference() {
        return difference;
    }

    public void setDifference(BigDecimal difference) {
        this.difference = difference;
    }

    public String getDenominationDetail() {
        return denominationDetail;
    }

    public void setDenominationDetail(String denominationDetail) {
        this.denominationDetail = denominationDetail;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
