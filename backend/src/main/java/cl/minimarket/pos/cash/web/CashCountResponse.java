package cl.minimarket.pos.cash.web;

import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.cash.domain.CashSessionCount;

import java.math.BigDecimal;

/**
 * Línea de arqueo por medio de pago en un turno cerrado.
 *
 * @param paymentMethod medio de pago
 * @param expectedAmount monto esperado en CLP
 * @param countedAmount monto contado en CLP
 * @param difference diferencia ({@code countedAmount - expectedAmount})
 */
public record CashCountResponse(
        PaymentMethod paymentMethod,
        BigDecimal expectedAmount,
        BigDecimal countedAmount,
        BigDecimal difference
) {
    /** Convierte una entidad {@link CashSessionCount} a DTO. */
    public static CashCountResponse from(CashSessionCount count) {
        return new CashCountResponse(
                count.getPaymentMethod(),
                count.getExpectedAmount(),
                count.getCountedAmount(),
                count.getDifference()
        );
    }
}
