package cl.minimarket.pos.sales.payment.domain;

import cl.minimarket.pos.sales.payment.CardAuthorizationStatus;
import cl.minimarket.pos.sales.payment.CardPaymentMethod;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
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

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad JPA para autorizaciones de tarjeta ({@code card_authorizations}, {@code PLAN.md} J-02).
 *
 * <p>Registra cada intento de cobro con tarjeta contra la pasarela antes de cerrar la venta. Por
 * cumplimiento de seguridad, nunca almacena el PAN completo, únicamente {@code cardLast4}.
 */
@Entity
@Table(name = "card_authorizations")
public class CardAuthorization extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "terminal_id", nullable = false)
    private Long terminalId;

    @Column(name = "cash_session_id", nullable = false)
    private Long cashSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 10)
    private CardPaymentMethod method;

    @Column(name = "amount", nullable = false, precision = 14, scale = 0)
    private BigDecimal amount;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private CardAuthorizationStatus status;

    @Column(name = "gateway_reference", length = 60)
    private String gatewayReference;

    @Column(name = "authorization_code", length = 20)
    private String authorizationCode;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_last4", length = 4, columnDefinition = "char(4)")
    private String cardLast4;

    @Column(name = "sale_id")
    private Long saleId;

    @Column(name = "message", length = 250)
    private String message;

    protected CardAuthorization() {
    }

    /**
     * Crea una nueva autorización de tarjeta con su resultado inicial de pasarela.
     *
     * @param storeId local donde se realizó el cobro
     * @param terminalId terminal POS origen
     * @param cashSessionId turno de caja abierto asociado
     * @param method medio de pago ({@code DEBIT} o {@code CREDIT})
     * @param amount monto en CLP (escala 0, mayor que cero)
     * @param idempotencyKey clave de idempotencia única
     * @param status estado inicial devuelto por la pasarela
     * @param gatewayReference referencia de la pasarela (opcional)
     * @param authorizationCode código de autorización del emisor (opcional)
     * @param cardBrand marca de la tarjeta (opcional)
     * @param cardLast4 últimos 4 dígitos de la tarjeta (máximo 4 caracteres, nunca PAN)
     * @param message mensaje de respuesta de la pasarela
     */
    public CardAuthorization(
            Long storeId,
            Long terminalId,
            Long cashSessionId,
            CardPaymentMethod method,
            BigDecimal amount,
            UUID idempotencyKey,
            CardAuthorizationStatus status,
            String gatewayReference,
            String authorizationCode,
            String cardBrand,
            String cardLast4,
            String message
    ) {
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.cashSessionId = Objects.requireNonNull(cashSessionId, "cashSessionId");
        this.method = Objects.requireNonNull(method, "method");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.status = Objects.requireNonNull(status, "status");
        this.gatewayReference = truncate(gatewayReference, 60);
        this.authorizationCode = truncate(authorizationCode, 20);
        this.cardBrand = truncate(cardBrand, 20);
        this.cardLast4 = normalizeLast4(cardLast4);
        this.message = truncate(message, 250);
    }

    /**
     * Marca la autorización como capturada al asociarse a una venta confirmada.
     *
     * @param saleId identificador de la venta que consume esta autorización
     * @throws BusinessException 409 {@code INVALID_STATE_TRANSITION} si no está {@code APPROVED} o ya tiene venta
     */
    public void markCaptured(Long saleId) {
        Objects.requireNonNull(saleId, "saleId");
        if (this.status != CardAuthorizationStatus.APPROVED || this.saleId != null) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "La autorización no está disponible para asociarse a una venta."
            );
        }
        this.saleId = saleId;
        this.status = CardAuthorizationStatus.CAPTURED;
    }

    /**
     * Anula una autorización aprobada que aún no ha sido asociada a ninguna venta.
     *
     * @param voidMessage mensaje descriptivo de la anulación
     * @throws BusinessException 409 {@code INVALID_STATE_TRANSITION} si ya fue usada o no está {@code APPROVED}
     */
    public void markVoided(String voidMessage) {
        if (this.status != CardAuthorizationStatus.APPROVED || this.saleId != null) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden anular autorizaciones aprobadas que no estén asociadas a una venta."
            );
        }
        this.status = CardAuthorizationStatus.VOIDED;
        this.message = truncate(voidMessage, 250);
    }

    private static String normalizeLast4(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > 4) {
            throw new IllegalArgumentException("cardLast4 no puede superar 4 caracteres");
        }
        return value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public Long getTerminalId() {
        return terminalId;
    }

    public Long getCashSessionId() {
        return cashSessionId;
    }

    public CardPaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public CardAuthorizationStatus getStatus() {
        return status;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public String getAuthorizationCode() {
        return authorizationCode;
    }

    public String getCardBrand() {
        return cardBrand;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public Long getSaleId() {
        return saleId;
    }

    public String getMessage() {
        return message;
    }
}
