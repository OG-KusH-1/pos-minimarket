package cl.minimarket.pos.security.users;

import cl.minimarket.pos.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Entidad JPA de la tabla {@code users} ({@code MODELO_DATOS.md} B.2) para los casos de uso de
 * administración de usuarios, reset de credenciales y control de acceso.
 */
@Entity
@Table(name = "users")
public class UserAccount extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "rut", length = 12)
    private String rut;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "pin_hash", length = 100)
    private String pinHash;

    @Column(name = "pin_failed_attempts", nullable = false)
    private int pinFailedAttempts;

    @Column(name = "pin_locked", nullable = false)
    private boolean pinLocked;

    @Column(name = "pin_locked_until")
    private Instant pinLockedUntil;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected UserAccount() {
    }

    /**
     * Crea una nueva cuenta de usuario activa con cambio de contraseña obligatorio.
     */
    public UserAccount(
            String username,
            String fullName,
            String email,
            String rut,
            String passwordHash,
            Instant passwordChangedAt
    ) {
        this.username = username;
        this.fullName = fullName;
        this.email = email;
        this.rut = rut;
        this.passwordHash = passwordHash;
        this.mustChangePassword = true;
        this.passwordChangedAt = passwordChangedAt;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getRut() {
        return rut;
    }

    public void setRut(String rut) {
        this.rut = rut;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPinHash() {
        return pinHash;
    }

    public void setPinHash(String pinHash) {
        this.pinHash = pinHash;
    }

    public int getPinFailedAttempts() {
        return pinFailedAttempts;
    }

    public void setPinFailedAttempts(int pinFailedAttempts) {
        this.pinFailedAttempts = pinFailedAttempts;
    }

    public boolean isPinLocked() {
        return pinLocked;
    }

    public void setPinLocked(boolean pinLocked) {
        this.pinLocked = pinLocked;
    }

    public Instant getPinLockedUntil() {
        return pinLockedUntil;
    }

    public void setPinLockedUntil(Instant pinLockedUntil) {
        this.pinLockedUntil = pinLockedUntil;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(Instant passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
