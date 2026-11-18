package cl.minimarket.pos.sales.web.sale;

import cl.minimarket.pos.sales.web.pos.CartLineInput;
import cl.minimarket.pos.sales.web.pos.DiscountInput;
import cl.minimarket.pos.sales.web.pos.PaymentInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Solicitud para confirmar una venta en el POS ({@code POST /sales}, {@code API.md} F.3
 * {@code CreateSaleRequest}).
 *
 * @param lines         líneas vendidas (entre 1 y 300)
 * @param totalDiscount descuento manual aplicado al total de la venta (opcional)
 * @param customerId    identificador del cliente asociado (opcional en Fase 1)
 * @param payments      medios de pago (entre 1 y 5)
 * @param ageConfirmed  confirmación de mayoría de edad cuando hay productos restringidos
 * @param emailTo       correo electrónico opcional para envío posterior de ticket
 */
public record CreateSaleRequest(
        @NotEmpty(message = "La venta debe incluir al menos una línea.")
        @Size(max = 300, message = "La venta no puede superar 300 líneas.")
        @Valid
        List<CartLineInput> lines,

        @Valid
        DiscountInput totalDiscount,

        Long customerId,

        @NotEmpty(message = "Debe informar al menos un medio de pago.")
        @Size(max = 5, message = "No se pueden registrar más de 5 pagos por venta.")
        @Valid
        List<PaymentInput> payments,

        Boolean ageConfirmed,

        @Email(message = "El correo electrónico no tiene un formato válido.")
        @Size(max = 254, message = "El correo electrónico no puede superar 254 caracteres.")
        String emailTo
) {
}
