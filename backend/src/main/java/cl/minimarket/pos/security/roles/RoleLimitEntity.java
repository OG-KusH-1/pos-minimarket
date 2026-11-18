package cl.minimarket.pos.security.roles;

import cl.minimarket.pos.security.RoleLimitCode;
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
 * Entidad JPA de la tabla {@code role_limits} ({@code MODELO_DATOS.md} B.2).
 *
 * <p>{@code value == null} representa límite ilimitado (∞). La ausencia de fila equivale a {@code 0}
 * según la semántica <em>fail-closed</em> de {@code ROLES_PERMISOS.md} D.3.
 */
@Entity
@Table(name = "role_limits")
public class RoleLimitEntity extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "limit_code", nullable = false, length = 40)
    private RoleLimitCode limitCode;

    @Column(name = "value", precision = 14, scale = 4)
    private BigDecimal value;

    protected RoleLimitEntity() {
    }

    /**
     * Crea una fila de límite para un rol.
     */
    public RoleLimitEntity(Long roleId, RoleLimitCode limitCode, BigDecimal value) {
        this.roleId = roleId;
        this.limitCode = limitCode;
        this.value = value;
    }

    public Long getId() {
        return id;
    }

    public Long getRoleId() {
        return roleId;
    }

    public RoleLimitCode getLimitCode() {
        return limitCode;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }
}
