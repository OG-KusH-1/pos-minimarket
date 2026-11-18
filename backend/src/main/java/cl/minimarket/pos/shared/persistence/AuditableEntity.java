package cl.minimarket.pos.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Superclase de las entidades con la notación <b>AUD</b> del modelo de datos (MODELO_DATOS,
 * "Notación"): {@code created_at, created_by, updated_at, updated_by, version}.
 *
 * <p>Los valores los completa Spring Data JPA Auditing ({@link PersistenceConfiguration}):
 * <ul>
 *   <li>{@code created_at}/{@code updated_at}: instante UTC de {@code BusinessClock};</li>
 *   <li>{@code created_by}/{@code updated_by}: id del usuario autenticado según
 *       {@link CurrentUserAuditor}; {@code null} si no hay (jobs, semillas, login);</li>
 *   <li>{@code version}: bloqueo optimista de JPA. Un conflicto sale como 409
 *       {@code CONCURRENT_MODIFICATION} (lo traduce {@code GlobalExceptionHandler}); los DTO de
 *       actualización deben incluir la versión que el cliente leyó (RN-24).</li>
 * </ul>
 *
 * <p>Esta clase <b>no declara el {@code id}</b>: cada entidad lo define con su estrategia
 * ({@code GenerationType.IDENTITY} para las columnas {@code GENERATED ALWAYS AS IDENTITY}).
 *
 * <p>Uso: {@code class Brand extends AuditableEntity { @Id ... }}. Los campos son de solo lectura
 * para el código de negocio (solo getters) porque los gestiona el framework. Entidades sin
 * alguna de estas columnas (p. ej. tablas append-only) no deben extender esta clase.
 *
 * <p>Punto de extensión: si una tabla AUD usara otro tipo de usuario (p. ej. UUID) habría que
 * parametrizar el auditor; hoy todas apuntan a {@code users.id} (bigint).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** @return instante de creación (UTC); {@code null} antes del primer guardado */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return id del usuario que creó la fila; {@code null} si fue el sistema */
    public Long getCreatedBy() {
        return createdBy;
    }

    /** @return instante de la última modificación (UTC) */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** @return id del usuario que modificó la fila por última vez; {@code null} si fue el sistema */
    public Long getUpdatedBy() {
        return updatedBy;
    }

    /** @return versión para bloqueo optimista; {@code null} en una entidad nueva sin guardar */
    public Long getVersion() {
        return version;
    }
}
