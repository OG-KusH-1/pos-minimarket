package cl.minimarket.pos.inventory.application.count;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Entidad raíz de una toma física de inventario ({@code inventory_counts}, MODELO_DATOS B.5).
 *
 * <p>Controla el ciclo de vida {@code OPEN -> SUBMITTED -> APPROVED} (o reabrir a {@code OPEN}
 * mediante {@code reject}, o cancelar a {@code CANCELLED}). El índice único parcial
 * {@code uq_inventory_counts_open_scope} garantiza a nivel de base de datos que no coexistan dos
 * tomas abiertas ({@code OPEN} o {@code SUBMITTED}) para el mismo alcance {@code (store_id,
 * COALESCE(category_id, 0))}.
 */
@Entity
@Table(name = "inventory_counts")
public class InventoryCount extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "number", nullable = false, unique = true, length = 24)
    private String number;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "count_type", nullable = false, length = 10)
    private CountType countType;

    @Column(name = "category_id")
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private CountStatus status;

    @Column(name = "blind", nullable = false)
    private boolean blind;

    @Column(name = "started_by", nullable = false)
    private Long startedBy;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", length = 250)
    private String rejectionReason;

    @Column(name = "notes")
    private String notes;

    protected InventoryCount() {
    }

    private InventoryCount(
            String number,
            Long storeId,
            CountType countType,
            Long categoryId,
            boolean blind,
            Long startedBy,
            String notes) {
        this.number = Objects.requireNonNull(number, "number");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.countType = Objects.requireNonNull(countType, "countType");
        this.categoryId = categoryId;
        this.status = CountStatus.OPEN;
        this.blind = blind;
        this.startedBy = Objects.requireNonNull(startedBy, "startedBy");
        this.notes = notes;
    }

    /**
     * Abre una nueva toma física de inventario en estado {@link CountStatus#OPEN}.
     *
     * @param number correlativo de tienda generado por {@code SequenceService}
     * @param storeId identificador del local activo
     * @param countType tipo de toma ({@code FULL} o {@code CYCLIC})
     * @param categoryId categoría raíz del alcance cíclico ({@code null} para {@code FULL})
     * @param blind {@code true} si oculta la existencia teórica al ejecutor sin permiso de aprobar
     * @param startedBy usuario que inicia la toma
     * @param notes notas opcionales
     * @return nueva instancia en estado {@code OPEN}
     */
    public static InventoryCount open(
            String number,
            Long storeId,
            CountType countType,
            Long categoryId,
            boolean blind,
            Long startedBy,
            String notes) {
        if (countType == CountType.CYCLIC && categoryId == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Una toma cíclica requiere categoryId");
        }
        if (countType == CountType.FULL && categoryId != null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Una toma completa (FULL) no debe especificar categoryId");
        }
        return new InventoryCount(number, storeId, countType, categoryId, blind, startedBy, notes);
    }

    /**
     * Marca la toma como enviada a aprobación ({@code OPEN -> SUBMITTED}).
     *
     * @param userId usuario que envía la toma
     * @param now instante de negocio UTC
     */
    public void submit(Long userId, Instant now) {
        if (this.status != CountStatus.OPEN) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se puede enviar a revisión una toma en estado OPEN (actual: " + this.status + ")");
        }
        this.status = CountStatus.SUBMITTED;
        this.submittedBy = Objects.requireNonNull(userId, "userId");
        this.submittedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Aprueba la toma ({@code SUBMITTED -> APPROVED}).
     *
     * @param approverId supervisor que aprueba (distinto de quien inició y de quienes contaron)
     * @param now instante de negocio UTC
     */
    public void approve(Long approverId, Instant now) {
        if (this.status != CountStatus.SUBMITTED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se puede aprobar una toma en estado SUBMITTED (actual: " + this.status + ")");
        }
        this.status = CountStatus.APPROVED;
        this.approvedBy = Objects.requireNonNull(approverId, "approverId");
        this.approvedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Rechaza la toma y la devuelve a {@code OPEN} para reconteo ({@code SUBMITTED -> OPEN}, API.md F.2).
     *
     * @param reason motivo del rechazo ingresado por el supervisor
     */
    public void rejectToOpen(String reason) {
        if (this.status != CountStatus.SUBMITTED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se puede rechazar una toma en estado SUBMITTED (actual: " + this.status + ")");
        }
        this.status = CountStatus.OPEN;
        this.rejectionReason = Objects.requireNonNull(reason, "reason");
        this.submittedBy = null;
        this.submittedAt = null;
    }

    /**
     * Cancela la toma ({@code OPEN | SUBMITTED -> CANCELLED}).
     *
     * @param reason motivo de cancelación
     */
    public void cancel(String reason) {
        if (this.status != CountStatus.OPEN && this.status != CountStatus.SUBMITTED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se puede cancelar una toma en estado OPEN o SUBMITTED (actual: " + this.status + ")");
        }
        this.status = CountStatus.CANCELLED;
        this.rejectionReason = Objects.requireNonNull(reason, "reason");
    }

    public Long getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public Long getStoreId() {
        return storeId;
    }

    public CountType getCountType() {
        return countType;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public CountStatus getStatus() {
        return status;
    }

    public boolean isBlind() {
        return blind;
    }

    public Long getStartedBy() {
        return startedBy;
    }

    public Long getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getNotes() {
        return notes;
    }
}
