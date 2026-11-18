package cl.minimarket.pos.sales.web.pos;

import cl.minimarket.pos.sales.application.pricing.DiscountKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Descuento manual solicitado sobre una línea o sobre el total del carrito
 * ({@code API.md} F.3 {@code DiscountInput}).
 *
 * @param kind   tipo de descuento ({@code PERCENT} o {@code AMOUNT})
 * @param value  porcentaje (> 0 y ≤ 100) o monto CLP (> 0)
 * @param reason motivo opcional del descuento
 */
public record DiscountInput(
        @NotNull(message = "El tipo de descuento es obligatorio.")
        DiscountKind kind,

        @NotNull(message = "El valor del descuento es obligatorio.")
        @DecimalMin(value = "0.0001", message = "El valor del descuento debe ser mayor que 0.")
        BigDecimal value,

        @Size(max = 250, message = "El motivo del descuento no puede superar 250 caracteres.")
        String reason
) {
}
