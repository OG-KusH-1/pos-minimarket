package cl.minimarket.pos.purchasing.productsupplier.domain;

import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entidad de asociación producto-proveedor ({@code product_suppliers}, {@code MODELO_DATOS.md} B.4).
 */
@Entity
@Table(name = "product_suppliers")
public class ProductSupplier extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "supplier_sku", length = 40)
    private String supplierSku;

    @Column(name = "purchase_unit_name", nullable = false, length = 30)
    private String purchaseUnitName = "Unidad";

    @Column(name = "units_per_purchase_unit", nullable = false, precision = 12, scale = 3)
    private BigDecimal unitsPerPurchaseUnit = BigDecimal.ONE;

    @Column(name = "cost_per_purchase_unit", nullable = false, precision = 14, scale = 4)
    private BigDecimal costPerPurchaseUnit;

    @Column(name = "min_order_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal minOrderQty = BigDecimal.ONE;

    @Column(name = "lead_time_days_override")
    private Integer leadTimeDaysOverride;

    @Column(name = "is_preferred", nullable = false)
    private boolean preferred = false;

    @Column(name = "last_purchase_at")
    private Instant lastPurchaseAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Constructor requerido por JPA. */
    public ProductSupplier() {
    }

    /**
     * Calcula el costo unitario neto por unidad base:
     * {@code round(costPerPurchaseUnit / unitsPerPurchaseUnit, 4)} (A.5).
     */
    public static BigDecimal computeUnitCostNet(BigDecimal costPerPurchaseUnit, BigDecimal unitsPerPurchaseUnit) {
        if (costPerPurchaseUnit == null || unitsPerPurchaseUnit == null || unitsPerPurchaseUnit.signum() <= 0) {
            return null;
        }
        return Rounding.unitCost(costPerPurchaseUnit.divide(unitsPerPurchaseUnit, Rounding.INTERMEDIATE));
    }

    /** Calcula el costo unitario neto de esta asociación. */
    public BigDecimal unitCostNet() {
        return computeUnitCostNet(costPerPurchaseUnit, unitsPerPurchaseUnit);
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

    public Long getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(Long supplierId) {
        this.supplierId = supplierId;
    }

    public String getSupplierSku() {
        return supplierSku;
    }

    public void setSupplierSku(String supplierSku) {
        this.supplierSku = supplierSku;
    }

    public String getPurchaseUnitName() {
        return purchaseUnitName;
    }

    public void setPurchaseUnitName(String purchaseUnitName) {
        this.purchaseUnitName = purchaseUnitName;
    }

    public BigDecimal getUnitsPerPurchaseUnit() {
        return unitsPerPurchaseUnit;
    }

    public void setUnitsPerPurchaseUnit(BigDecimal unitsPerPurchaseUnit) {
        this.unitsPerPurchaseUnit = unitsPerPurchaseUnit;
    }

    public BigDecimal getCostPerPurchaseUnit() {
        return costPerPurchaseUnit;
    }

    public void setCostPerPurchaseUnit(BigDecimal costPerPurchaseUnit) {
        this.costPerPurchaseUnit = costPerPurchaseUnit;
    }

    public BigDecimal getMinOrderQty() {
        return minOrderQty;
    }

    public void setMinOrderQty(BigDecimal minOrderQty) {
        this.minOrderQty = minOrderQty;
    }

    public Integer getLeadTimeDaysOverride() {
        return leadTimeDaysOverride;
    }

    public void setLeadTimeDaysOverride(Integer leadTimeDaysOverride) {
        this.leadTimeDaysOverride = leadTimeDaysOverride;
    }

    public boolean isPreferred() {
        return preferred;
    }

    public void setPreferred(boolean preferred) {
        this.preferred = preferred;
    }

    public Instant getLastPurchaseAt() {
        return lastPurchaseAt;
    }

    public void setLastPurchaseAt(Instant lastPurchaseAt) {
        this.lastPurchaseAt = lastPurchaseAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
