package cl.minimarket.pos.catalog.scale.domain;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad de configuración de códigos de barras de balanza ({@code scale_barcode_configs},
 * {@code MODELO_DATOS} B.3).
 *
 * <p>Define el prefijo EAN-13 (2 o 3 dígitos) y las posiciones del PLU y del valor embebido
 * (peso o precio) dentro de los 13 dígitos del código.
 */
@Entity
@Table(name = "scale_barcode_configs")
public class ScaleBarcodeConfig extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "prefix", nullable = false, unique = true, length = 3)
    private String prefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "embedded_type", nullable = false, length = 10)
    private EmbeddedType embeddedType;

    @Column(name = "plu_start", nullable = false)
    private short pluStart;

    @Column(name = "plu_length", nullable = false)
    private short pluLength;

    @Column(name = "value_start", nullable = false)
    private short valueStart;

    @Column(name = "value_length", nullable = false)
    private short valueLength;

    @Column(name = "value_decimals", nullable = false)
    private short valueDecimals;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Constructor requerido por JPA. */
    public ScaleBarcodeConfig() {
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

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public EmbeddedType getEmbeddedType() {
        return embeddedType;
    }

    public void setEmbeddedType(EmbeddedType embeddedType) {
        this.embeddedType = embeddedType;
    }

    public short getPluStart() {
        return pluStart;
    }

    public void setPluStart(short pluStart) {
        this.pluStart = pluStart;
    }

    public short getPluLength() {
        return pluLength;
    }

    public void setPluLength(short pluLength) {
        this.pluLength = pluLength;
    }

    public short getValueStart() {
        return valueStart;
    }

    public void setValueStart(short valueStart) {
        this.valueStart = valueStart;
    }

    public short getValueLength() {
        return valueLength;
    }

    public void setValueLength(short valueLength) {
        this.valueLength = valueLength;
    }

    public short getValueDecimals() {
        return valueDecimals;
    }

    public void setValueDecimals(short valueDecimals) {
        this.valueDecimals = valueDecimals;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
