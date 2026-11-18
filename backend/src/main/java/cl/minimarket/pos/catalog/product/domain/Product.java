package cl.minimarket.pos.catalog.product.domain;

import cl.minimarket.pos.catalog.RestrictedType;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
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

/**
 * Entidad de producto del catálogo ({@code products}, MODELO_DATOS B.3).
 */
@Entity
@Table(name = "products")
public class Product extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku", nullable = false, unique = true, length = 40)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_type", nullable = false, length = 10)
    private SaleType saleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_of_measure", nullable = false, length = 5)
    private UnitOfMeasure unitOfMeasure;

    @Column(name = "price_gross", nullable = false, precision = 12, scale = 0)
    private BigDecimal priceGross = BigDecimal.ZERO;

    @Column(name = "avg_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal avgCost = BigDecimal.ZERO;

    @Column(name = "last_cost", precision = 14, scale = 4)
    private BigDecimal lastCost;

    @Column(name = "vat_exempt", nullable = false)
    private boolean vatExempt = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "restricted_type", length = 10)
    private RestrictedType restrictedType;

    @Column(name = "lot_controlled", nullable = false)
    private boolean lotControlled = false;

    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;

    @Column(name = "image_file_id")
    private Long imageFileId;

    @Column(name = "shortcut_code", length = 10)
    private String shortcutCode;

    @Column(name = "allow_manual_discount", nullable = false)
    private boolean allowManualDiscount = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Constructor requerido por JPA. */
    public Product() {
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getBrandId() {
        return brandId;
    }

    public void setBrandId(Long brandId) {
        this.brandId = brandId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public SaleType getSaleType() {
        return saleType;
    }

    public void setSaleType(SaleType saleType) {
        this.saleType = saleType;
    }

    public UnitOfMeasure getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public void setUnitOfMeasure(UnitOfMeasure unitOfMeasure) {
        this.unitOfMeasure = unitOfMeasure;
    }

    public BigDecimal getPriceGross() {
        return priceGross;
    }

    public void setPriceGross(BigDecimal priceGross) {
        this.priceGross = priceGross;
    }

    public BigDecimal getAvgCost() {
        return avgCost;
    }

    public void setAvgCost(BigDecimal avgCost) {
        this.avgCost = avgCost;
    }

    public BigDecimal getLastCost() {
        return lastCost;
    }

    public void setLastCost(BigDecimal lastCost) {
        this.lastCost = lastCost;
    }

    public boolean isVatExempt() {
        return vatExempt;
    }

    public void setVatExempt(boolean vatExempt) {
        this.vatExempt = vatExempt;
    }

    public RestrictedType getRestrictedType() {
        return restrictedType;
    }

    public void setRestrictedType(RestrictedType restrictedType) {
        this.restrictedType = restrictedType;
    }

    public boolean isLotControlled() {
        return lotControlled;
    }

    public void setLotControlled(boolean lotControlled) {
        this.lotControlled = lotControlled;
    }

    public Integer getShelfLifeDays() {
        return shelfLifeDays;
    }

    public void setShelfLifeDays(Integer shelfLifeDays) {
        this.shelfLifeDays = shelfLifeDays;
    }

    public Long getImageFileId() {
        return imageFileId;
    }

    public void setImageFileId(Long imageFileId) {
        this.imageFileId = imageFileId;
    }

    public String getShortcutCode() {
        return shortcutCode;
    }

    public void setShortcutCode(String shortcutCode) {
        this.shortcutCode = shortcutCode;
    }

    public boolean isAllowManualDiscount() {
        return allowManualDiscount;
    }

    public void setAllowManualDiscount(boolean allowManualDiscount) {
        this.allowManualDiscount = allowManualDiscount;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
