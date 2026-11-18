package cl.minimarket.pos.catalog.product.domain;

import cl.minimarket.pos.catalog.BarcodeType;
import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad de código de barras asociado a un producto ({@code product_barcodes}, B.3).
 */
@Entity
@Table(name = "product_barcodes")
public class ProductBarcode extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "barcode_type", nullable = false, length = 10)
    private BarcodeType barcodeType;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    /** Constructor requerido por JPA. */
    public ProductBarcode() {
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public BarcodeType getBarcodeType() {
        return barcodeType;
    }

    public void setBarcodeType(BarcodeType barcodeType) {
        this.barcodeType = barcodeType;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }
}
