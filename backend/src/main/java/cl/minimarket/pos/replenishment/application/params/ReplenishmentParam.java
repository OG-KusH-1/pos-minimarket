package cl.minimarket.pos.replenishment.application.params;

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
 * Entidad JPA de parámetros de reabastecimiento por alcance ({@code replenishment_params},
 * {@code MODELO_DATOS.md} B.11).
 *
 * <p>Cada columna de parámetro es anulable: {@code null} significa "hereda del alcance superior"
 * en la resolución de {@link ReplenishmentParamsResolver}. Las filas se borran físicamente desde
 * su endpoint, salvo la de alcance {@link ReplenishmentParamScope#GLOBAL}, que es el default del
 * sistema y no se puede borrar.
 */
@Entity
@Table(name = "replenishment_params")
public class ReplenishmentParam extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10)
    private ReplenishmentParamScope scope;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "window_days")
    private Integer windowDays;

    @Column(name = "min_history_days")
    private Integer minHistoryDays;

    @Column(name = "service_level_z", precision = 5, scale = 3)
    private BigDecimal serviceLevelZ;

    @Column(name = "safety_min_days", precision = 6, scale = 2)
    private BigDecimal safetyMinDays;

    @Column(name = "review_period_days")
    private Integer reviewPeriodDays;

    @Column(name = "outlier_k", precision = 5, scale = 2)
    private BigDecimal outlierK;

    @Column(name = "shelf_life_factor", precision = 4, scale = 3)
    private BigDecimal shelfLifeFactor;

    @Column(name = "enabled")
    private Boolean enabled;

    /** Constructor requerido por JPA. */
    public ReplenishmentParam() {
    }

    /**
     * Crea la fila con el alcance y sus claves ya validados por el servicio.
     *
     * @param scope             alcance de la fila
     * @param storeId           local (según la matriz de coherencia del alcance)
     * @param categoryId        categoría (solo CATEGORY)
     * @param productId         producto (solo PRODUCT)
     * @param windowDays        ventana W, o {@code null} para heredar
     * @param minHistoryDays    mínimo de días válidos, o {@code null} para heredar
     * @param serviceLevelZ     factor z, o {@code null} para heredar
     * @param safetyMinDays     días mínimos de seguridad Smin, o {@code null} para heredar
     * @param reviewPeriodDays  período R explícito, o {@code null} para heredar (y usar el automático)
     * @param outlierK          multiplicador k del recorte, o {@code null} para heredar
     * @param shelfLifeFactor   fracción f de la vida útil, o {@code null} para heredar
     * @param enabled           participación en las corridas, o {@code null} para heredar
     */
    public ReplenishmentParam(ReplenishmentParamScope scope,
                              Long storeId,
                              Long categoryId,
                              Long productId,
                              Integer windowDays,
                              Integer minHistoryDays,
                              BigDecimal serviceLevelZ,
                              BigDecimal safetyMinDays,
                              Integer reviewPeriodDays,
                              BigDecimal outlierK,
                              BigDecimal shelfLifeFactor,
                              Boolean enabled) {
        this.scope = scope;
        this.storeId = storeId;
        this.categoryId = categoryId;
        this.productId = productId;
        this.windowDays = windowDays;
        this.minHistoryDays = minHistoryDays;
        this.serviceLevelZ = serviceLevelZ;
        this.safetyMinDays = safetyMinDays;
        this.reviewPeriodDays = reviewPeriodDays;
        this.outlierK = outlierK;
        this.shelfLifeFactor = shelfLifeFactor;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public ReplenishmentParamScope getScope() {
        return scope;
    }

    public Long getStoreId() {
        return storeId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public Long getProductId() {
        return productId;
    }

    public Integer getWindowDays() {
        return windowDays;
    }

    public Integer getMinHistoryDays() {
        return minHistoryDays;
    }

    public BigDecimal getServiceLevelZ() {
        return serviceLevelZ;
    }

    public BigDecimal getSafetyMinDays() {
        return safetyMinDays;
    }

    public Integer getReviewPeriodDays() {
        return reviewPeriodDays;
    }

    public BigDecimal getOutlierK() {
        return outlierK;
    }

    public BigDecimal getShelfLifeFactor() {
        return shelfLifeFactor;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    /**
     * Reemplaza las columnas de parámetros por las del comando de upsert: el PUT es un reemplazo
     * completo de las columnas (una columna que llega {@code null} pasa a heredar del nivel
     * superior). El alcance y sus claves no cambian.
     */
    public void applyValues(Integer windowDays,
                            Integer minHistoryDays,
                            BigDecimal serviceLevelZ,
                            BigDecimal safetyMinDays,
                            Integer reviewPeriodDays,
                            BigDecimal outlierK,
                            BigDecimal shelfLifeFactor,
                            Boolean enabled) {
        this.windowDays = windowDays;
        this.minHistoryDays = minHistoryDays;
        this.serviceLevelZ = serviceLevelZ;
        this.safetyMinDays = safetyMinDays;
        this.reviewPeriodDays = reviewPeriodDays;
        this.outlierK = outlierK;
        this.shelfLifeFactor = shelfLifeFactor;
        this.enabled = enabled;
    }
}
