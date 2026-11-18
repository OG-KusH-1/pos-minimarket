package cl.minimarket.pos.sales.web.suspended;

import cl.minimarket.pos.sales.web.pos.CartLineInput;
import cl.minimarket.pos.sales.web.pos.DiscountInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Contenido estructurado del carrito guardado en una venta suspendida ({@code API.md} F.3).
 *
 * @param lines líneas del carrito (1 a 300)
 * @param totalDiscount descuento manual opcional aplicado al total del carrito
 */
public record SuspendedSaleCart(
        @NotEmpty(message = "El carrito suspendido debe incluir al menos una línea")
        @Size(max = 300, message = "El carrito suspendido admite hasta 300 líneas")
        @Valid
        List<CartLineInput> lines,
        @Valid
        DiscountInput totalDiscount
) {
}
