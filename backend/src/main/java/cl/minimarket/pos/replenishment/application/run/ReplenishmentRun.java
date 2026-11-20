package cl.minimarket.pos.replenishment.application.run;

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

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Entidad JPA de corrida de reabastecimiento ({@code replenishment_runs}, {@code MODELO_DATOS.md} B.11).
 *
 * <p>{@code paramsSnapshot} guarda el contexto de parámetros con que corrió (jsonb): el rango
 * refrescado del agregado y la ventana máxima aplicada, para que la corrida sea reproducible
 * aunque los parámetros cambien después.
 */
@Entity
@Table(name = "replenishment_runs")
public class ReplenishmentRun extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private ReplenishmentRunStatus status = ReplenishmentRunStatus.RUNNING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_snapshot", nullable = false, columnDefinition = "jsonb")
    private String paramsSnapshot;

    @Column(name = "lines_count", nullable = false)
    private int linesCount = 0;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error")
    private String error;

    /** Constructor requerido por JPA. */
    public ReplenishmentRun() {
    }

    /**
     * Crea la corrida en estado {@link ReplenishmentRunStatus#RUNNING}.
     *
     * @param storeId        local de la corrida
     * @param asOfDate       fecha de corte (la ventana termina el día anterior)
     * @param paramsSnapshot contexto de parámetros (JSON serializado)
     * @param startedAt      instante de inicio
     */
    public ReplenishmentRun(Long storeId, LocalDate asOfDate, String paramsSnapshot, Instant startedAt) {
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.asOfDate = Objects.requireNonNull(asOfDate, "asOfDate");
        this.paramsSnapshot = Objects.requireNonNull(paramsSnapshot, "paramsSnapshot");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public ReplenishmentRunStatus getStatus() {
        return status;
    }

    public String getParamsSnapshot() {
        return paramsSnapshot;
    }

    public int getLinesCount() {
        return linesCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getError() {
        return error;
    }

    /** Transiciona la corrida a {@link ReplenishmentRunStatus#COMPLETED} con su conteo de líneas. */
    public void complete(int linesCount, Instant finishedAt) {
        this.status = ReplenishmentRunStatus.COMPLETED;
        this.linesCount = linesCount;
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
    }
}
