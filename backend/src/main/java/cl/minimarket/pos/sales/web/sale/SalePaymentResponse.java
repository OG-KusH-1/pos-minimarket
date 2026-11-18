package cl.minimarket.pos.sales.web.sale;

import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import cl.minimarket.pos.sales.application.sale.SalePayment;
import cl.minimarket.pos.sales.application.sale.SalePaymentStatus;

import java.math.BigDecimal;

/**
 * Representación pública de un pago registrado en una venta ({@code API.md} F.3
 * {@code SalePaymentResponse}).
 */
public record SalePaymentResponse(
        Long id,
        PaymentMethod method,
        BigDecimal amount,
        BigDecimal tenderedAmount,
        BigDecimal changeAmount,
        SalePaymentStatus status,
        String cardBrand,
        String last4,
        String authorizationCode,
        String transferReference
) {

    /** Convierte una entidad {@link SalePayment} al DTO de respuesta. */
    public static SalePaymentResponse from(SalePayment payment) {
        return new SalePaymentResponse(
                payment.getId(),
                payment.getMethod(),
                payment.getAmount(),
                payment.getTenderedAmount(),
                payment.getChangeAmount(),
                payment.getStatus(),
                payment.getCardBrand(),
                payment.getCardLast4(),
                payment.getAuthorizationCode(),
                payment.getTransferReference()
        );
    }
}
