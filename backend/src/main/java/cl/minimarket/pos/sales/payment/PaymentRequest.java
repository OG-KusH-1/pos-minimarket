package cl.minimarket.pos.sales.payment;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitud de autorización enviada al puerto {@link PaymentGateway} ({@code PLAN.md} A.4).
 *
 * <p>Por seguridad (PCI / HU-POS-09), nunca incluye el PAN completo de la tarjeta.
 *
 * @param terminalId identificador de la terminal POS desde la que se cobra
 * @param method tipo de tarjeta ({@link CardPaymentMethod#DEBIT} o {@link CardPaymentMethod#CREDIT})
 * @param amount monto en CLP (escala 0, mayor que cero)
 * @param idempotencyKey clave de idempotencia de la operación
 * @param reference referencia interna opcional para trazabilidad en la pasarela
 */
public record PaymentRequest(
        Long terminalId,
        CardPaymentMethod method,
        BigDecimal amount,
        UUID idempotencyKey,
        String reference
) {
    /**
     * Valida que los campos obligatorios de la solicitud no sean nulos y que el monto sea positivo.
     */
    public PaymentRequest {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount debe ser mayor que cero");
        }
    }
}
