package cl.minimarket.pos.catalog.product.domain;

import cl.minimarket.pos.catalog.PriceChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Registro inmutable (append-only) de cambios de precio de venta o costo ({@code price_history},
 * MODELO_DATOS B.3 / RN-16 / RN-35).
 */
@Entity
@Table(name = "price_history")
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private PriceChangeType changeType;

    @Column(name = "old_price_gross", precision = 12, scale = 0)
    private BigDecimal oldPriceGross;

    @Column(name = "new_price_gross", precision = 12, scale = 0)
    private BigDecimal newPriceGross;

    @Column(name = "old_cost", precision = 14, scale = 4)
    private BigDecimal oldCost;

    @Column(name = "new_cost", precision = 14, scale = 4)
    private BigDecimal newCost;

    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "reason", length = 250)
    private String reason;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    /** Constructor requerido por JPA. */
    public PriceHistory() {
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public PriceChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(PriceChangeType changeType) {
        this.changeType = changeType;
    }

    public BigDecimal getOldPriceGross() {
        return oldPriceGross;
    }

    public void setOldPriceGross(BigDecimal oldPriceGross) {
        this.oldPriceGross = oldPriceGross;
    }

    public BigDecimal getNewPriceGross() {
        return newPriceGross;
    }

    public void setNewPriceGross(BigDecimal newPriceGross) {
        this.newPriceGross = newPriceGross;
    }

    public BigDecimal getOldCost() {
        return oldCost;
    }

    public void setOldCost(BigDecimal oldCost) {
        this.oldCost = oldCost;
    }

    public BigDecimal getNewCost() {
        return newCost;
    }

    public void setNewCost(BigDecimal newCost) {
        this.newCost = newCost;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(Long changedBy) {
        this.changedBy = changedBy;
    }

    public OffsetDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(OffsetDateTime changedAt) {
        this.changedAt = changedAt;
    }
}
