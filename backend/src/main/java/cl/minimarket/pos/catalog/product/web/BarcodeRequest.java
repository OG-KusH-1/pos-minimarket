package cl.minimarket.pos.catalog.product.web;

import cl.minimarket.pos.catalog.BarcodeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Solicitud para asociar un código de barras a un producto ({@code API.md} F.3).
 */
public record BarcodeRequest(
        @NotBlank(message = "El código de barras es obligatorio.")
        @Pattern(regexp = "^[0-9A-Za-z]{3,20}$",
                message = "El código de barras debe tener entre 3 y 20 caracteres alfanuméricos.")
        String code,

        @NotNull(message = "El tipo de código de barras es obligatorio.")
        BarcodeType barcodeType,

        Boolean isPrimary
) {
}
