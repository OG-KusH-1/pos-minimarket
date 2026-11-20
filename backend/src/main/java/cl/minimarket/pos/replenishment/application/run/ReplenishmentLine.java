package cl.minimarket.pos.replenishment.application.run;

import cl.minimarket.pos.replenishment.domain.ReplenishmentStatus;
import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Entidad JPA de línea de sugerencia de reabastecimiento ({@code replenishment_lines},
 * {@code MODELO_DATOS.md} B.11): una fila por producto y corrida (UQ {@code (run_id, product_id)}).
 *
 * <p>Los valores numéricos conservan las escalas del resultado del calculador: métricas y niveles
 * a cuatro decimales, cantidades a tres y cobertura a dos. {@code flags} mapea el arreglo
 * {@code varchar(30)[]} de la tabla con los nombres del enum {@code replenishment_flag}.
 */
@Entity
@Table(name = "replenishment_lines")
public class ReplenishmentLine extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "product_supplier_id")
    private Long productSupplierId;

    @Column(name = "valid_days", nullable = false)
    private int validDays;

    @Column(name = "censored_days", nullable = false)
    private int censoredDays;

    @Column(name = "avg_daily_demand", nullable = false, precision = 14, scale = 4)
    private BigDecimal avgDailyDemand;

    @Column(name = "demand_std", nullable = false, precision = 14, scale = 4)
    private BigDecimal demandStd;

    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;

    @Column(name = "review_period_days", nullable = false)
    private int reviewPeriodDays;

    @Column(name = "on_hand", nullable = false, precision = 14, scale = 3)
    private BigDecimal onHand;

    @Column(name = "in_transit", nullable = false, precision = 14, scale = 3)
    private BigDecimal inTransit;

    @Column(name = "safety_stock", nullable = false, precision = 14, scale = 4)
    private BigDecimal safetyStock;

    @Column(name = "reorder_point", nullable = false, precision = 14, scale = 4)
    private BigDecimal reorderPoint;

    @Column(name = "target_level", nullable = false, precision = 14, scale = 4)
    private BigDecimal targetLevel;

    @Column(name = "suggested_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal suggestedQty;

    @Column(name = "suggested_purchase_units", nullable = false, precision = 12, scale = 3)
    private BigDecimal suggestedPurchaseUnits;

    @Column(name = "final_purchase_units", precision = 12, scale = 3)
    private BigDecimal finalPurchaseUnits;

    @Column(name = "unit_cost_net", precision = 14, scale = 4)
    private BigDecimal unitCostNet;

    @Column(name = "estimated_stockout_date")
    private LocalDate estimatedStockoutDate;

    @Column(name = "days_of_cover", precision = 10, scale = 2)
    private BigDecimal daysOfCover;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private ReplenishmentStatus status;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "flags", columnDefinition = "varchar(30)[]", nullable = false)
    private String[] flags = new String[0];

    /** Constructor requerido por JPA. */
    public ReplenishmentLine() {
    }

    /**
     * Crea la línea desde el resultado del calculador; todas las escalas ya vienen normalizadas.
     *
     * @param runId corrida dueña de la línea
     * @param result resultado del calculador para el producto
     */
    public ReplenishmentLine(Long runId, cl.minimarket.pos.replenishment.domain.ReplenishmentResult result) {
        this.runId = runId;
        this.productId = result.productId();
        this.supplierId = result.supplierId();
        this.productSupplierId = result.productSupplierId();
        this.validDays = result.validDays();
        this.censoredDays = result.censoredDays();
        this.avgDailyDemand = result.averageDailyDemand();
        this.demandStd = result.demandStandardDeviation();
        this.leadTimeDays = result.leadTimeDays();
        this.reviewPeriodDays = result.reviewPeriodDays();
        this.onHand = result.onHand();
        this.inTransit = result.inTransit();
        this.safetyStock = result.safetyStock();
        this.reorderPoint = result.reorderPoint();
        this.targetLevel = result.targetLevel();
        this.suggestedQty = result.suggestedQuantity();
        this.suggestedPurchaseUnits = result.suggestedPurchaseUnits();
        this.unitCostNet = result.unitCostNet();
        this.estimatedStockoutDate = result.estimatedStockoutDate();
        this.daysOfCover = result.daysOfCover();
        this.status = result.status();
        this.flags = result.flags().stream().map(Enum::name).toArray(String[]::new);
    }

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getSupplierId() {
        return supplierId;
    }

    public Long getProductSupplierId() {
        return productSupplierId;
    }

    public int getValidDays() {
        return validDays;
    }

    public int getCensoredDays() {
        return censoredDays;
    }

    public BigDecimal getAvgDailyDemand() {
        return avgDailyDemand;
    }

    public BigDecimal getDemandStd() {
        return demandStd;
    }

    public int getLeadTimeDays() {
        return leadTimeDays;
    }

    public int getReviewPeriodDays() {
        return reviewPeriodDays;
    }

    public BigDecimal getOnHand() {
        return onHand;
    }

    public BigDecimal getInTransit() {
        return inTransit;
    }

    public BigDecimal getSafetyStock() {
        return safetyStock;
    }

    public BigDecimal getReorderPoint() {
        return reorderPoint;
    }

    public BigDecimal getTargetLevel() {
        return targetLevel;
    }

    public BigDecimal getSuggestedQty() {
        return suggestedQty;
    }

    public BigDecimal getSuggestedPurchaseUnits() {
        return suggestedPurchaseUnits;
    }

    public BigDecimal getFinalPurchaseUnits() {
        return finalPurchaseUnits;
    }

    public BigDecimal getUnitCostNet() {
        return unitCostNet;
    }

    public LocalDate getEstimatedStockoutDate() {
        return estimatedStockoutDate;
    }

    public BigDecimal getDaysOfCover() {
        return daysOfCover;
    }

    public ReplenishmentStatus getStatus() {
        return status;
    }

    public List<String> getFlags() {
        return Arrays.asList(flags);
    }

    /** Unidades finales efectivas de la sugerencia: las editadas o, si no, las sugeridas. */
    public BigDecimal effectivePurchaseUnits() {
        return finalPurchaseUnits != null ? finalPurchaseUnits : suggestedPurchaseUnits;
    }

    /** Fija las unidades finales editadas por el usuario (escala 3, ya validadas). */
    public void setFinalPurchaseUnits(BigDecimal finalPurchaseUnits) {
        this.finalPurchaseUnits = finalPurchaseUnits;
    }

    /**
     * Reelige el proveedor de la línea: cambia el proveedor, la asociación elegida y el snapshot
     * del costo neto por unidad base. El lead time y el período de revisión conservan el valor
     * del cálculo original (el recálculo completo llega con una corrida nueva).
     */
    public void setSupplier(Long supplierId, Long productSupplierId, BigDecimal unitCostNet) {
        this.supplierId = supplierId;
        this.productSupplierId = productSupplierId;
        this.unitCostNet = unitCostNet;
    }

    /** Cambia el estado de la línea (transiciones validadas por el servicio). */
    public void setStatus(ReplenishmentStatus status) {
        this.status = status;
    }
}
