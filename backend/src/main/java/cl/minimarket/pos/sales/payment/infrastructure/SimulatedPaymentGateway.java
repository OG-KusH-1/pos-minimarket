package cl.minimarket.pos.sales.payment.infrastructure;

import cl.minimarket.pos.sales.payment.CardPaymentMethod;
import cl.minimarket.pos.sales.payment.PaymentGateway;
import cl.minimarket.pos.sales.payment.PaymentRequest;
import cl.minimarket.pos.sales.payment.PaymentResult;
import cl.minimarket.pos.sales.payment.PaymentResultStatus;
import cl.minimarket.pos.sales.payment.RefundRequest;
import cl.minimarket.pos.sales.payment.RefundResult;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * Adaptador simulado de {@link PaymentGateway} para el MVP ({@code PLAN.md} A.4, J-02).
 *
 * <p>Reglas determinísticas para demos y pruebas:
 * <ul>
 *   <li>Montos terminados en {@code 13}: responde {@link PaymentResultStatus#DECLINED}.</li>
 *   <li>Montos terminados en {@code 99}: responde {@link PaymentResultStatus#ERROR}.</li>
 *   <li>Cualquier otro monto positivo: responde {@link PaymentResultStatus#APPROVED} con marca y
 *       últimos 4 dígitos simulados (nunca PAN completo).</li>
 * </ul>
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DECLINE_SUFFIX = BigDecimal.valueOf(13);
    private static final BigDecimal ERROR_SUFFIX = BigDecimal.valueOf(99);

    @Override
    public PaymentResult authorize(PaymentRequest request) {
        BigDecimal normalizedAmount = Rounding.clp(request.amount());
        BigDecimal lastTwoDigits = normalizedAmount.remainder(HUNDRED);

        if (lastTwoDigits.compareTo(DECLINE_SUFFIX) == 0) {
            return new PaymentResult(
                    PaymentResultStatus.DECLINED,
                    newReference("SIM-DEC-"),
                    null,
                    brandFor(request.method()),
                    null,
                    "PAYMENT_DECLINED",
                    "Transacción rechazada por el emisor (simulación monto terminado en 13)"
            );
        }

        if (lastTwoDigits.compareTo(ERROR_SUFFIX) == 0) {
            return new PaymentResult(
                    PaymentResultStatus.ERROR,
                    null,
                    null,
                    null,
                    null,
                    "GATEWAY_ERROR",
                    "Error de comunicación con la pasarela de pago (simulación monto terminado en 99)"
            );
        }

        String authCode = String.format("%06d", Math.floorMod(request.idempotencyKey().hashCode(), 1_000_000));
        String last4 = request.method() == CardPaymentMethod.DEBIT ? "1234" : "4242";
        return new PaymentResult(
                PaymentResultStatus.APPROVED,
                newReference("SIM-"),
                authCode,
                brandFor(request.method()),
                last4,
                null,
                "Autorización aprobada"
        );
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        return new RefundResult(
                true,
                newReference("SIM-REF-"),
                null,
                "Reversa simulada aprobada"
        );
    }

    @Override
    public String providerCode() {
        return "SIMULATED";
    }

    private static String brandFor(CardPaymentMethod method) {
        return method == CardPaymentMethod.DEBIT ? "REDCOMPRA" : "VISA";
    }

    private static String newReference(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                .toUpperCase(Locale.ROOT);
    }
}
