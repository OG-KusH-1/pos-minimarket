package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.sales.application.pricing.DiscountKind;
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

/**
 * Descuento aplicado en una venta, manual o por promoción, sobre línea o total
 * ({@code sale_discounts}, {@code MODELO_DATOS.md} B.9).
 */
@Entity
@Table(name = "sale_discounts")
public class SaleDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_id", nullable = false)
    private Long saleId;

    @Column(name = "sale_line_id")
    private Long saleLineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private SaleDiscountSource source;

    @Column(name = "promotion_id")
    private Long promotionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_kind", nullable = false, length = 10)
    private DiscountKind discountKind;

    @Column(name = "value", nullable = false, precision = 12, scale = 4)
    private BigDecimal value;

    @Column(name = "amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal amount;

    @Column(name = "reason", length = 250)
    private String reason;

    @Column(name = "applied_by", nullable = false)
    private Long appliedBy;

    @Column(name = "authorization_id")
    private Long authorizationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SaleDiscount() {
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

    public Long getSaleLineId() {
        return saleLineId;
    }

    public void setSaleLineId(Long saleLineId) {
        this.saleLineId = saleLineId;
    }

    public SaleDiscountSource getSource() {
        return source;
    }

    public void setSource(SaleDiscountSource source) {
        this.source = source;
    }

    public Long getPromotionId() {
        return promotionId;
    }

    public void setPromotionId(Long promotionId) {
        this.promotionId = promotionId;
    }

    public DiscountKind getDiscountKind() {
        return discountKind;
    }

    public void setDiscountKind(DiscountKind discountKind) {
        this.discountKind = discountKind;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getAppliedBy() {
        return appliedBy;
    }

    public void setAppliedBy(Long appliedBy) {
        this.appliedBy = appliedBy;
    }

    public Long getAuthorizationId() {
        return authorizationId;
    }

    public void setAuthorizationId(Long authorizationId) {
        this.authorizationId = authorizationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
