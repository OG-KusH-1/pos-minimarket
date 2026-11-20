package cl.minimarket.pos.customers.domain;

import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.shared.money.Rounding;
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
 * Movimiento inmutable (append-only) del libro de cuenta corriente de clientes
 * ({@code customer_account_movements}, {@code MODELO_DATOS.md} B.7).
 *
 * <p>Un {@code amount} positivo aumenta la deuda ({@code CHARGE} o ajuste positivo); uno negativo
 * la disminuye ({@code PAYMENT}, {@code REVERSAL} o ajuste negativo).
 */
@Entity
@Table(name = "customer_account_movements")
public class CustomerAccountMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 15)
    private CustomerMovementType movementType;

    @Column(name = "amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 14, scale = 0)
    private BigDecimal balanceAfter;

    @Column(name = "sale_id")
    private Long saleId;

    @Column(name = "sale_return_id")
    private Long saleReturnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "cash_session_id")
    private Long cashSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "notes", length = 250)
    private String notes;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected CustomerAccountMovement() {
    }

    /** Crea un nuevo movimiento de cuenta corriente listo para persistir. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public CustomerAccountMovement(
            Long customerId,
            Long storeId,
            CustomerMovementType movementType,
            BigDecimal amount,
            BigDecimal balanceAfter,
            Long saleId,
            Long saleReturnId,
            PaymentMethod paymentMethod,
            Long cashSessionId,
            Long userId,
            UUID idempotencyKey,
            String notes,
            Instant occurredAt
    ) {
        this.customerId = customerId;
        this.storeId = storeId;
        this.movementType = movementType;
        this.amount = Rounding.clp(amount);
        this.balanceAfter = Rounding.clp(balanceAfter);
        this.saleId = saleId;
        this.saleReturnId = saleReturnId;
        this.paymentMethod = paymentMethod;
        this.cashSessionId = cashSessionId;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.notes = notes;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getStoreId() {
        return storeId;
    }

    public CustomerMovementType getMovementType() {
        return movementType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Long getSaleId() {
        return saleId;
    }

    public Long getSaleReturnId() {
        return saleReturnId;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public Long getCashSessionId() {
        return cashSessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
