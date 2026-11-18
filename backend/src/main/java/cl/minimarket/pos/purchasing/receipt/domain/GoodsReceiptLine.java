package cl.minimarket.pos.purchasing.receipt.domain;

import cl.minimarket.pos.shared.money.Rounding;
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
import java.time.LocalDate;
import java.util.Objects;

/**
 * Entidad JPA de línea de recepción de mercadería ({@code goods_receipt_lines}, {@code MODELO_DATOS.md} B.4).
 */
@Entity
@Table(name = "goods_receipt_lines")
public class GoodsReceiptLine extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_receipt_id", nullable = false)
    private GoodsReceipt goodsReceipt;

    @Column(name = "purchase_order_line_id")
    private Long purchaseOrderLineId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "qty_expected", precision = 14, scale = 3)
    private BigDecimal qtyExpected;

    @Column(name = "qty_received", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyReceived;

    @Column(name = "qty_rejected", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyRejected = BigDecimal.ZERO;

    @Column(name = "difference_reason", length = 250)
    private String differenceReason;

    @Column(name = "unit_cost_net", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCostNet;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "lot_id")
    private Long lotId;

    protected GoodsReceiptLine() {
    }

    /**
     * Crea una línea de recepción con cantidades y costo unitario ya normalizados.
     */
    public GoodsReceiptLine(Long purchaseOrderLineId,
                            Long productId,
                            BigDecimal qtyExpected,
                            BigDecimal qtyReceived,
                            BigDecimal qtyRejected,
                            String differenceReason,
                            BigDecimal unitCostNet,
                            String lotNumber,
                            LocalDate expiryDate) {
        this.purchaseOrderLineId = purchaseOrderLineId;
        this.productId = Objects.requireNonNull(productId, "productId");
        this.qtyExpected = qtyExpected;
        this.qtyReceived = Objects.requireNonNull(qtyReceived, "qtyReceived");
        this.qtyRejected = Objects.requireNonNull(qtyRejected, "qtyRejected");
        this.differenceReason = differenceReason;
        this.unitCostNet = Objects.requireNonNull(unitCostNet, "unitCostNet");
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
    }

    void attachTo(GoodsReceipt goodsReceipt) {
        this.goodsReceipt = goodsReceipt;
    }

    /** Cantidad neta aceptada que ingresa a stock y afecta el costo promedio ({@code qty_received - qty_rejected}). */
    public BigDecimal qtyAccepted() {
        return Rounding.quantity(qtyReceived.subtract(qtyRejected));
    }

    public Long getId() {
        return id;
    }

    public Long getPurchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public Long getProductId() {
        return productId;
    }

    public BigDecimal getQtyExpected() {
        return qtyExpected;
    }

    public BigDecimal getQtyReceived() {
        return qtyReceived;
    }

    public BigDecimal getQtyRejected() {
        return qtyRejected;
    }

    public String getDifferenceReason() {
        return differenceReason;
    }

    public BigDecimal getUnitCostNet() {
        return unitCostNet;
    }

    public String getLotNumber() {
        return lotNumber;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public Long getLotId() {
        return lotId;
    }

    public void setLotId(Long lotId) {
        this.lotId = lotId;
    }
}
