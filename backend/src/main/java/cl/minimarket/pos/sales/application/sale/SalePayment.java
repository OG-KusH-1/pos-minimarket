package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
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
 * Pago registrado en una venta ({@code sale_payments}, {@code MODELO_DATOS.md} B.9).
 */
@Entity
@Table(name = "sale_payments")
public class SalePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_id", nullable = false)
    private Long saleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal amount;

    @Column(name = "tendered_amount", precision = 14, scale = 0)
    private BigDecimal tenderedAmount;

    @Column(name = "change_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal changeAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private SalePaymentStatus status = SalePaymentStatus.APPROVED;

    @Column(name = "gateway_reference", length = 60)
    private String gatewayReference;

    @Column(name = "authorization_code", length = 20)
    private String authorizationCode;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "transfer_reference", length = 60)
    private String transferReference;

    @Column(name = "card_authorization_id")
    private Long cardAuthorizationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SalePayment() {
    }

    public Long getId() {
        return id;
    }

    public Long getSaleId() {
        return saleId;
    }

    public void setSaleId(Long saleId) {
        this.saleId = saleId;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public void setMethod(PaymentMethod method) {
        this.method = method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getTenderedAmount() {
        return tenderedAmount;
    }

    public void setTenderedAmount(BigDecimal tenderedAmount) {
        this.tenderedAmount = tenderedAmount;
    }

    public BigDecimal getChangeAmount() {
        return changeAmount;
    }

    public void setChangeAmount(BigDecimal changeAmount) {
        this.changeAmount = changeAmount;
    }

    public SalePaymentStatus getStatus() {
        return status;
    }

    public void setStatus(SalePaymentStatus status) {
        this.status = status;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public void setGatewayReference(String gatewayReference) {
        this.gatewayReference = gatewayReference;
    }

    public String getAuthorizationCode() {
        return authorizationCode;
    }

    public void setAuthorizationCode(String authorizationCode) {
        this.authorizationCode = authorizationCode;
    }

    public String getCardBrand() {
        return cardBrand;
    }

    public void setCardBrand(String cardBrand) {
        this.cardBrand = cardBrand;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public void setCardLast4(String cardLast4) {
        this.cardLast4 = cardLast4;
    }

    public String getTransferReference() {
        return transferReference;
    }

    public void setTransferReference(String transferReference) {
        this.transferReference = transferReference;
    }

    public Long getCardAuthorizationId() {
        return cardAuthorizationId;
    }

    public void setCardAuthorizationId(Long cardAuthorizationId) {
        this.cardAuthorizationId = cardAuthorizationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
