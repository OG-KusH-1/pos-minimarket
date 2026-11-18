package cl.minimarket.pos.sales.web.pos;

import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Medio de pago incluido en la cotización del carrito o creación de venta
 * ({@code API.md} F.3 {@code PaymentInput}).
 *
 * @param method              medio de pago
 * @param amount              monto aplicado (obligatorio salvo {@code CASH}, ≥ 1)
 * @param tenderedAmount      monto entregado en efectivo (solo {@code CASH})
 * @param cardAuthorizationId id de autorización previa de tarjeta (para {@code DEBIT} o {@code CREDIT})
 * @param transferReference   referencia de transferencia (para {@code TRANSFER})
 */
public record PaymentInput(
        @NotNull(message = "El medio de pago es obligatorio.")
        PaymentMethod method,

        @DecimalMin(value = "1", message = "El monto del pago debe ser al menos 1.")
        BigDecimal amount,

        @DecimalMin(value = "0", message = "El monto entregado no puede ser negativo.")
        BigDecimal tenderedAmount,

        Long cardAuthorizationId,

        @Size(max = 60, message = "La referencia de transferencia no puede superar 60 caracteres.")
        String transferReference
) {
}
