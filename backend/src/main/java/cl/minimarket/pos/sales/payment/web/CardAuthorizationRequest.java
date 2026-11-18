package cl.minimarket.pos.sales.payment.web;

import cl.minimarket.pos.sales.payment.CardPaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Cuerpo de solicitud para {@code POST /api/v1/pos/card-payments} ({@code API.md} F.3).
 *
 * @param method tipo de tarjeta ({@code DEBIT} o {@code CREDIT})
 * @param amount monto en CLP a autorizar ({@code >= 1})
 */
public record CardAuthorizationRequest(
        @NotNull(message = "El medio de pago es obligatorio")
        CardPaymentMethod method,

        @NotNull(message = "El monto es obligatorio")
        @DecimalMin(value = "1", message = "El monto debe ser mayor o igual a 1")
        @Digits(integer = 14, fraction = 0, message = "El monto debe ser un entero CLP sin decimales")
        BigDecimal amount
) {
}
