package cl.minimarket.pos.sales.payment;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitud de reversa o reembolso enviada a {@link PaymentGateway#refund(RefundRequest)}
 * ({@code PLAN.md} A.4).
 *
 * @param gatewayReference referencia original entregada por la pasarela en la autorización
 * @param amount monto en CLP a reversar o reembolsar (mayor que cero)
 * @param idempotencyKey clave de idempotencia de la reversa
 */
public record RefundRequest(
        String gatewayReference,
        BigDecimal amount,
        UUID idempotencyKey
) {
    /** Valida que los campos requeridos estén presentes y el monto sea positivo. */
    public RefundRequest {
        Objects.requireNonNull(gatewayReference, "gatewayReference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount debe ser mayor que cero");
        }
    }
}
