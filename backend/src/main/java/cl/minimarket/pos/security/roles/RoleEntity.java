package cl.minimarket.pos.security.roles;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad JPA de la tabla {@code roles} ({@code MODELO_DATOS.md} B.2).
 *
 * <p>Los roles base marcados con {@code system = true} no se pueden eliminar, aunque sus permisos
 * y límites sí son editables (con protección del último {@code ADMIN} activo).
 */
@Entity
@Table(name = "roles")
public class RoleEntity extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 30, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "description", length = 250)
    private String description;

    @Column(name = "system", nullable = false)
    private boolean system;

    protected RoleEntity() {
    }

    /**
     * Crea un nuevo rol editable (no de sistema).
     */
    public RoleEntity(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.system = false;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isSystem() {
        return system;
    }
}
