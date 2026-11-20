package cl.minimarket.pos.promotions.domain;

import cl.minimarket.pos.promotions.PromotionTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Ítem de alcance de una promoción ({@code promotion_items}, {@code MODELO_DATOS.md} B.10).
 *
 * <p>Hija de reemplazo: se reescribe completa al reeditar la promoción.
 */
@Entity
@Table(name = "promotion_items")
public class PromotionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private PromotionTargetType targetType;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "required_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal requiredQty = BigDecimal.ONE;

    public Long getId() {
        return id;
    }

    public Promotion getPromotion() {
        return promotion;
    }

    public void setPromotion(Promotion promotion) {
        this.promotion = promotion;
    }

    public PromotionTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(PromotionTargetType targetType) {
        this.targetType = targetType;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getBrandId() {
        return brandId;
    }

    public void setBrandId(Long brandId) {
        this.brandId = brandId;
    }

    public BigDecimal getRequiredQty() {
        return requiredQty;
    }

    public void setRequiredQty(BigDecimal requiredQty) {
        this.requiredQty = requiredQty;
    }

    /**
     * Devuelve el identificador del objetivo según {@link #getTargetType()}.
     *
     * @return id de producto, categoría o marca
     */
    public Long targetId() {
        return switch (targetType) {
            case PRODUCT -> productId;
            case CATEGORY -> categoryId;
            case BRAND -> brandId;
        };
    }
}
