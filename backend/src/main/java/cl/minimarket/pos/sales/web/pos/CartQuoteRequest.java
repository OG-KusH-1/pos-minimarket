package cl.minimarket.pos.sales.web.pos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Solicitud de cotización del carrito en POS ({@code API.md} F.3 {@code CartQuoteRequest}).
 *
 * @param lines         líneas del carrito (entre 1 y 300)
 * @param totalDiscount descuento manual al total (opcional)
 * @param customerId    identificador del cliente asociado (opcional)
 * @param payments      medios de pago propuestos para calcular redondeo y vuelto (opcional)
 */
public record CartQuoteRequest(
        @NotEmpty(message = "El carrito debe incluir al menos una línea.")
        @Size(max = 300, message = "El carrito no puede superar 300 líneas.")
        @Valid
        List<CartLineInput> lines,

        @Valid
        DiscountInput totalDiscount,

        Long customerId,

        @Valid
        List<PaymentInput> payments
) {
}
