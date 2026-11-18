package cl.minimarket.pos.cash.domain;

import cl.minimarket.pos.cash.CashMovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Movimiento manual de efectivo (ingreso o retiro) dentro de un turno ({@code cash_movements}).
 *
 * <p>Es una tabla append-only con clave de idempotencia única ({@code idempotency_key}).
 */
@Entity
@Table(name = "cash_movements")
public class CashMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cash_session_id", nullable = false)
    private Long cashSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 10)
    private CashMovementType movementType;

    @Column(name = "amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 250)
    private String reason;

    @Column(name = "authorization_id")
    private Long authorizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Constructor requerido por JPA. */
    public CashMovement() {
    }

    public Long getId() {
        return id;
    }

    public Long getCashSessionId() {
        return cashSessionId;
    }

    public void setCashSessionId(Long cashSessionId) {
        this.cashSessionId = cashSessionId;
    }

    public CashMovementType getMovementType() {
        return movementType;
    }

    public void setMovementType(CashMovementType movementType) {
        this.movementType = movementType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getAuthorizationId() {
        return authorizationId;
    }

    public void setAuthorizationId(Long authorizationId) {
        this.authorizationId = authorizationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(UUID idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
