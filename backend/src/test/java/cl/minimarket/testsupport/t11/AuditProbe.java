package cl.minimarket.testsupport.t11;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad de prueba que extiende {@link AuditableEntity} sobre la tabla {@code roles} (tabla AUD
 * real, sin dependencias). Vive fuera del paquete {@code cl.minimarket.pos} a propósito: así el
 * escaneo de entidades de la aplicación no la incorpora en otros contextos de prueba; solo la
 * registra {@code AuditableEntityIT} con {@code @EntityScan}.
 */
@Entity(name = "T11AuditProbe")
@Table(name = "roles")
public class AuditProbe extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "system", nullable = false)
    private boolean system;

    /** Constructor para JPA. */
    protected AuditProbe() {
    }

    public AuditProbe(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
