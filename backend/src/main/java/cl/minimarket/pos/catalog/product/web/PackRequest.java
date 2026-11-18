package cl.minimarket.pos.catalog.product.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Solicitud para definir o actualizar un pack ({@code API.md} F.3).
 */
public record PackRequest(
        @NotNull(message = "El producto base es obligatorio.")
        Long baseProductId,

        @NotNull(message = "El factor es obligatorio.")
        @Min(value = 2, message = "El factor mínimo de un pack es 2.")
        @Max(value = 1000, message = "El factor máximo de un pack es 1000.")
        Integer factor
) {
}
