package cl.minimarket.pos.customers.domain;

import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Entidad JPA de cliente ({@code customers}, {@code MODELO_DATOS.md} B.7).
 *
 * <p>Almacena los datos de identificación y la configuración de cuenta corriente (fiado). Los
 * clientes nunca se eliminan físicamente: se desactivan con {@code active = false} cuando no
 * tienen deuda vigente.
 */
@Entity
@Table(name = "customers")
public class Customer extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rut", length = 12)
    private String rut;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "address", length = 250)
    private String address;

    @Column(name = "credit_enabled", nullable = false)
    private boolean creditEnabled;

    @Column(name = "credit_limit", nullable = false, precision = 14, scale = 0)
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @Column(name = "credit_balance", nullable = false, precision = 14, scale = 0)
    private BigDecimal creditBalance = BigDecimal.ZERO;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Customer() {
    }

    /**
     * Crea un cliente nuevo sin crédito habilitado y con saldo cero.
     */
    public Customer(String rut, String name, String email, String phone, String address, String notes) {
        this.rut = rut;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.address = address;
        this.notes = notes;
        this.creditEnabled = false;
        this.creditLimit = BigDecimal.ZERO;
        this.creditBalance = BigDecimal.ZERO;
        this.active = true;
    }

    /**
     * Calcula el cupo de crédito disponible en CLP, acotado a cero cuando la deuda iguala o supera
     * el límite configurado.
     */
    public BigDecimal availableCredit() {
        BigDecimal available = Rounding.clp(creditLimit).subtract(Rounding.clp(creditBalance));
        return available.compareTo(BigDecimal.ZERO) > 0 ? available : BigDecimal.ZERO;
    }

    public Long getId() {
        return id;
    }

    public String getRut() {
        return rut;
    }

    public void setRut(String rut) {
        this.rut = rut;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public boolean isCreditEnabled() {
        return creditEnabled;
    }

    public void setCreditEnabled(boolean creditEnabled) {
        this.creditEnabled = creditEnabled;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = Rounding.clp(creditLimit);
    }

    public BigDecimal getCreditBalance() {
        return creditBalance;
    }

    public void setCreditBalance(BigDecimal creditBalance) {
        this.creditBalance = Rounding.clp(creditBalance);
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
