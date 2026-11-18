package cl.minimarket.pos.catalog.product.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Solicitud de cambio de precio de venta ({@code API.md} F.3).
 */
public record PriceChangeRequest(
        @NotNull(message = "El nuevo precio es obligatorio.")
        @DecimalMin(value = "1", message = "El precio debe ser mayor o igual a 1.")
        BigDecimal newPriceGross,

        @NotBlank(message = "El motivo es obligatorio.")
        @Size(min = 3, max = 250, message = "El motivo debe tener entre 3 y 250 caracteres.")
        String reason,

        @NotNull(message = "La versión del producto es obligatoria.")
        Long version
) {
}
