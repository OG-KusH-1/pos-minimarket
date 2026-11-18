package cl.minimarket.pos.organization.domain;

import cl.minimarket.pos.organization.SettingScope;
import cl.minimarket.pos.organization.SettingValueType;
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

/**
 * Entidad de parámetro clave-valor ({@code settings}, MODELO_DATOS B.1).
 */
@Entity
@Table(name = "settings")
public class Setting extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10)
    private SettingScope scope;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "key", nullable = false, length = 100)
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 10)
    private SettingValueType valueType;

    @Column(name = "description", length = 250)
    private String description;

    /** Constructor requerido por JPA. */
    public Setting() {
    }

    public Long getId() {
        return id;
    }

    public SettingScope getScope() {
        return scope;
    }

    public void setScope(SettingScope scope) {
        this.scope = scope;
    }

    public Long getStoreId() {
        return storeId;
    }

    public void setStoreId(Long storeId) {
        this.storeId = storeId;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public SettingValueType getValueType() {
        return valueType;
    }

    public void setValueType(SettingValueType valueType) {
        this.valueType = valueType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
