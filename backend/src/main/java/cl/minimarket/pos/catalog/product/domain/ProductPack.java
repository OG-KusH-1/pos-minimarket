package cl.minimarket.pos.catalog.product.domain;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Entidad de definición de pack ({@code product_packs}, MODELO_DATOS B.3 / ADR-17).
 *
 * <p>Vender {@code N} packs descuenta {@code N × factor} unidades del producto base {@code UNIT}.
 */
@Entity
@Table(name = "product_packs")
public class ProductPack extends AuditableEntity {

    @Id
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "base_product_id", nullable = false)
    private Long baseProductId;

    @Column(name = "factor", nullable = false, precision = 12, scale = 3)
    private BigDecimal factor;

    /** Constructor requerido por JPA. */
    public ProductPack() {
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getBaseProductId() {
        return baseProductId;
    }

    public void setBaseProductId(Long baseProductId) {
        this.baseProductId = baseProductId;
    }

    public BigDecimal getFactor() {
        return factor;
    }

    public void setFactor(BigDecimal factor) {
        this.factor = factor;
    }
}
