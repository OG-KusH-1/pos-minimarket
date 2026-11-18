package cl.minimarket.pos.sales.application.sale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Snapshot del desglose de un impuesto específico aplicado a una línea de venta
 * ({@code sale_line_taxes}, {@code MODELO_DATOS.md} B.9).
 */
@Entity
@Table(name = "sale_line_taxes")
public class SaleLineTax {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_line_id", nullable = false)
    private Long saleLineId;

    @Column(name = "tax_id", nullable = false)
    private Long taxId;

    @Column(name = "tax_code", nullable = false, length = 30)
    private String taxCode;

    @Column(name = "rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal rate;

    @Column(name = "base_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal baseAmount;

    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal taxAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SaleLineTax() {
    }

    public Long getId() {
        return id;
    }

    public Long getSaleLineId() {
        return saleLineId;
    }

    public void setSaleLineId(Long saleLineId) {
        this.saleLineId = saleLineId;
    }

    public Long getTaxId() {
        return taxId;
    }

    public void setTaxId(Long taxId) {
        this.taxId = taxId;
    }

    public String getTaxCode() {
        return taxCode;
    }

    public void setTaxCode(String taxCode) {
        this.taxCode = taxCode;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public void setBaseAmount(BigDecimal baseAmount) {
        this.baseAmount = baseAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
