package cl.minimarket.pos.purchasing.order.domain;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Entidad JPA de línea de orden de compra ({@code purchase_order_lines}, {@code MODELO_DATOS.md} B.4).
 */
@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_supplier_id")
    private Long productSupplierId;

    @Column(name = "qty_purchase_units", nullable = false, precision = 12, scale = 3)
    private BigDecimal qtyPurchaseUnits;

    @Column(name = "units_per_purchase_unit", nullable = false, precision = 12, scale = 3)
    private BigDecimal unitsPerPurchaseUnit;

    @Column(name = "qty_ordered", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyOrdered;

    @Column(name = "unit_cost_net", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCostNet;

    @Column(name = "qty_received", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyReceived = BigDecimal.ZERO;

    @Column(name = "line_net_total", nullable = false, precision = 14, scale = 0)
    private BigDecimal lineNetTotal;

    @Column(name = "replenishment_line_id")
    private Long replenishmentLineId;

    protected PurchaseOrderLine() {
    }

    /**
     * Crea una línea de orden de compra con cantidades, costos y totales ya redondeados.
     */
    public PurchaseOrderLine(Long productId,
                             Long productSupplierId,
                             BigDecimal qtyPurchaseUnits,
                             BigDecimal unitsPerPurchaseUnit,
                             BigDecimal qtyOrdered,
                             BigDecimal unitCostNet,
                             BigDecimal lineNetTotal,
                             Long replenishmentLineId) {
        this.productId = Objects.requireNonNull(productId, "productId");
        this.productSupplierId = productSupplierId;
        this.qtyPurchaseUnits = Objects.requireNonNull(qtyPurchaseUnits, "qtyPurchaseUnits");
        this.unitsPerPurchaseUnit = Objects.requireNonNull(unitsPerPurchaseUnit, "unitsPerPurchaseUnit");
        this.qtyOrdered = Objects.requireNonNull(qtyOrdered, "qtyOrdered");
        this.unitCostNet = Objects.requireNonNull(unitCostNet, "unitCostNet");
        this.lineNetTotal = Objects.requireNonNull(lineNetTotal, "lineNetTotal");
        this.replenishmentLineId = replenishmentLineId;
        this.qtyReceived = BigDecimal.ZERO;
    }

    void attachTo(PurchaseOrder purchaseOrder) {
        this.purchaseOrder = purchaseOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getProductSupplierId() {
        return productSupplierId;
    }

    public BigDecimal getQtyPurchaseUnits() {
        return qtyPurchaseUnits;
    }

    public BigDecimal getUnitsPerPurchaseUnit() {
        return unitsPerPurchaseUnit;
    }

    public BigDecimal getQtyOrdered() {
        return qtyOrdered;
    }

    public BigDecimal getUnitCostNet() {
        return unitCostNet;
    }

    public BigDecimal getQtyReceived() {
        return qtyReceived;
    }

    public BigDecimal getLineNetTotal() {
        return lineNetTotal;
    }

    public Long getReplenishmentLineId() {
        return replenishmentLineId;
    }
}
