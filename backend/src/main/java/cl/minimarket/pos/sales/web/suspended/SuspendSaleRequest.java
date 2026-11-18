package cl.minimarket.pos.sales.web.suspended;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Petición para suspender temporalmente el carrito actual de la terminal POS ({@code API.md} F.3).
 *
 * @param label etiqueta breve para identificar al cliente o pedido en espera (máx. 60 caracteres)
 * @param customerId identificador opcional del cliente asociado
 * @param cart contenido del carrito a suspender
 */
public record SuspendSaleRequest(
        @Size(max = 60, message = "La etiqueta no puede superar los 60 caracteres")
        String label,
        Long customerId,
        @NotNull(message = "El carrito es obligatorio")
        @Valid
        SuspendedSaleCart cart
) {
}
