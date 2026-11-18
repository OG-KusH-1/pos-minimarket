package cl.minimarket.pos.security.roles;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad JPA de solo lectura de la tabla {@code permissions} ({@code MODELO_DATOS.md} B.2).
 *
 * <p>El catálogo de permisos se siembra por migración y no se modifica en tiempo de ejecución.
 */
@Entity
@Table(name = "permissions")
public class PermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 60, unique = true)
    private String code;

    @Column(name = "module", nullable = false, length = 30)
    private String module;

    @Column(name = "description", nullable = false, length = 250)
    private String description;

    @Column(name = "sensitive", nullable = false)
    private boolean sensitive;

    protected PermissionEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getModule() {
        return module;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSensitive() {
        return sensitive;
    }
}
