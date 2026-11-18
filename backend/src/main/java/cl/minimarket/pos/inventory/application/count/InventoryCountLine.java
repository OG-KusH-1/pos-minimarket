package cl.minimarket.pos.inventory.application.count;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
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
import java.util.Objects;

/**
 * Línea individual de una toma de inventario ({@code inventory_count_lines}, MODELO_DATOS B.5).
 *
 * <p>Al generarse la toma, {@code theoreticalQty}, {@code countedQty} y {@code unitCost} inician en
 * {@code null}. Al registrar el conteo en {@code PUT /lines}, se toma el snapshot exacto de
 * {@code theoreticalQty} (existencia en ese instante) y {@code unitCost} junto con la cantidad
 * contada, el usuario y la fecha (RN-20). Así, cualquier venta o movimiento ocurrido entre ese
 * instante y la aprobación se preserva al aplicar únicamente el delta {@code counted_qty -
 * theoretical_qty}.
 */
@Entity
@Table(name = "inventory_count_lines")
public class InventoryCountLine extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "count_id", nullable = false)
    private Long countId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "lot_id")
    private Long lotId;

    @Column(name = "theoretical_qty", precision = 14, scale = 3)
    private BigDecimal theoreticalQty;

    @Column(name = "counted_qty", precision = 14, scale = 3)
    private BigDecimal countedQty;

    @Column(name = "unit_cost", precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "counted_by")
    private Long countedBy;

    @Column(name = "counted_at")
    private Instant countedAt;

    @Column(name = "stock_movement_id")
    private Long stockMovementId;

    protected InventoryCountLine() {
    }

    private InventoryCountLine(Long countId, Long productId, Long lotId) {
        this.countId = Objects.requireNonNull(countId, "countId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.lotId = lotId;
    }

    /**
     * Crea una línea pendiente de conteo para una toma abierta.
     *
     * @param countId identificador de la toma
     * @param productId identificador del producto
     * @param lotId identificador del lote si la línea desglosa por lote ({@code null} si no)
     * @return línea sin conteo registrado aún
     */
    public static InventoryCountLine createPending(Long countId, Long productId, Long lotId) {
        return new InventoryCountLine(countId, productId, lotId);
    }

    /**
     * Registra o actualiza el conteo de la línea capturando en ese instante la existencia teórica
     * y el costo unitario vigentes (RN-20).
     *
     * @param theoreticalQty existencia actual del producto/lote al momento de registrar el conteo
     * @param countedQty cantidad física contada ({@code >= 0})
     * @param unitCost costo unitario neto vigente (puede ser {@code null} si aún no tiene costo)
     * @param countedBy usuario que registra el conteo
     * @param countedAt instante UTC del conteo
     */
    public void recordCount(
            BigDecimal theoreticalQty,
            BigDecimal countedQty,
            BigDecimal unitCost,
            Long countedBy,
            Instant countedAt) {
        Objects.requireNonNull(theoreticalQty, "theoreticalQty");
        Objects.requireNonNull(countedQty, "countedQty");
        BigDecimal normalizedCounted = Rounding.quantity(countedQty);
        if (normalizedCounted.signum() < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "La cantidad contada no puede ser negativa");
        }
        this.theoreticalQty = Rounding.quantity(theoreticalQty);
        this.countedQty = normalizedCounted;
        this.unitCost = unitCost != null ? Rounding.unitCost(unitCost) : null;
        this.countedBy = Objects.requireNonNull(countedBy, "countedBy");
        this.countedAt = Objects.requireNonNull(countedAt, "countedAt");
    }

    /**
     * Vincula la línea con el movimiento de kardex generado al aprobar la toma.
     *
     * @param stockMovementId identificador en {@code stock_movements}
     * @param appliedUnitCost costo unitario aplicado en el movimiento (si no es nulo, actualiza)
     */
    public void linkStockMovement(Long stockMovementId, BigDecimal appliedUnitCost) {
        this.stockMovementId = Objects.requireNonNull(stockMovementId, "stockMovementId");
        if (appliedUnitCost != null) {
            this.unitCost = Rounding.unitCost(appliedUnitCost);
        }
    }

    /**
     * Calcula la diferencia {@code countedQty - theoreticalQty} con escala 3.
     *
     * @return diferencia si la línea ya fue contada, o {@code null} en caso contrario
     */
    public BigDecimal difference() {
        if (countedQty == null || theoreticalQty == null) {
            return null;
        }
        return Rounding.quantity(countedQty.subtract(theoreticalQty));
    }

    public boolean isCounted() {
        return countedAt != null && countedQty != null;
    }

    public Long getId() {
        return id;
    }

    public Long getCountId() {
        return countId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getLotId() {
        return lotId;
    }

    public BigDecimal getTheoreticalQty() {
        return theoreticalQty;
    }

    public BigDecimal getCountedQty() {
        return countedQty;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public Long getCountedBy() {
        return countedBy;
    }

    public Instant getCountedAt() {
        return countedAt;
    }

    public Long getStockMovementId() {
        return stockMovementId;
    }
}
