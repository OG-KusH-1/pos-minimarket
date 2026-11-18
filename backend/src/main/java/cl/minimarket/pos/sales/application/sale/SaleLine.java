package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.catalog.SaleType;
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

/**
 * Línea de venta con snapshot de SKU, descripción, precio y costo ({@code sale_lines},
 * {@code MODELO_DATOS.md} B.9).
 */
@Entity
@Table(name = "sale_lines")
public class SaleLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_id", nullable = false)
    private Long saleId;

    @Column(name = "line_number", nullable = false)
    private short lineNumber;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sku", nullable = false, length = 40)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "scanned_code", length = 20)
    private String scannedCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_type", nullable = false, length = 10)
    private SaleType saleType;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "stock_product_id", nullable = false)
    private Long stockProductId;

    @Column(name = "stock_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal stockQuantity;

    @Column(name = "unit_price_gross", nullable = false, precision = 12, scale = 0)
    private BigDecimal unitPriceGross;

    @Column(name = "line_subtotal_gross", nullable = false, precision = 14, scale = 0)
    private BigDecimal lineSubtotalGross;

    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "line_total_gross", nullable = false, precision = 14, scale = 0)
    private BigDecimal lineTotalGross;

    @Column(name = "line_net", nullable = false, precision = 14, scale = 0)
    private BigDecimal lineNet;

    @Column(name = "line_tax", nullable = false, precision = 14, scale = 0)
    private BigDecimal lineTax;

    @Column(name = "line_exempt", nullable = false, precision = 14, scale = 0)
    private BigDecimal lineExempt = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "line_cost", nullable = false, precision = 16, scale = 2)
    private BigDecimal lineCost;

    @Column(name = "restricted", nullable = false)
    private boolean restricted;

    @Column(name = "returned_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SaleLine() {
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

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = (short) lineNumber;
    }

    public Long getStoreId() {
        return storeId;
    }

    public void setStoreId(Long storeId) {
        this.storeId = storeId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getScannedCode() {
        return scannedCode;
    }

    public void setScannedCode(String scannedCode) {
        this.scannedCode = scannedCode;
    }

    public SaleType getSaleType() {
        return saleType;
    }

    public void setSaleType(SaleType saleType) {
        this.saleType = saleType;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public Long getStockProductId() {
        return stockProductId;
    }

    public void setStockProductId(Long stockProductId) {
        this.stockProductId = stockProductId;
    }

    public BigDecimal getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(BigDecimal stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public BigDecimal getUnitPriceGross() {
        return unitPriceGross;
    }

    public void setUnitPriceGross(BigDecimal unitPriceGross) {
        this.unitPriceGross = unitPriceGross;
    }

    public BigDecimal getLineSubtotalGross() {
        return lineSubtotalGross;
    }

    public void setLineSubtotalGross(BigDecimal lineSubtotalGross) {
        this.lineSubtotalGross = lineSubtotalGross;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getLineTotalGross() {
        return lineTotalGross;
    }

    public void setLineTotalGross(BigDecimal lineTotalGross) {
        this.lineTotalGross = lineTotalGross;
    }

    public BigDecimal getLineNet() {
        return lineNet;
    }

    public void setLineNet(BigDecimal lineNet) {
        this.lineNet = lineNet;
    }

    public BigDecimal getLineTax() {
        return lineTax;
    }

    public void setLineTax(BigDecimal lineTax) {
        this.lineTax = lineTax;
    }

    public BigDecimal getLineExempt() {
        return lineExempt;
    }

    public void setLineExempt(BigDecimal lineExempt) {
        this.lineExempt = lineExempt;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public void setUnitCost(BigDecimal unitCost) {
        this.unitCost = unitCost;
    }

    public BigDecimal getLineCost() {
        return lineCost;
    }

    public void setLineCost(BigDecimal lineCost) {
        this.lineCost = lineCost;
    }

    public boolean isRestricted() {
        return restricted;
    }

    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }

    public BigDecimal getReturnedQuantity() {
        return returnedQuantity;
    }

    public void setReturnedQuantity(BigDecimal returnedQuantity) {
        this.returnedQuantity = returnedQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
