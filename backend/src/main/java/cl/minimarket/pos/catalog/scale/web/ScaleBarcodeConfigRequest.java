package cl.minimarket.pos.catalog.scale.web;

import cl.minimarket.pos.catalog.scale.domain.EmbeddedType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Datos de entrada para crear o actualizar una configuración de código de balanza
 * ({@code API.md} F.3).
 */
public record ScaleBarcodeConfigRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(min = 2, max = 60, message = "El nombre debe tener entre 2 y 60 caracteres.")
        String name,

        @NotBlank(message = "El prefijo es obligatorio.")
        @Pattern(regexp = "^\\d{2,3}$", message = "El prefijo debe tener 2 o 3 dígitos.")
        String prefix,

        @NotNull(message = "El tipo de valor embebido es obligatorio.")
        EmbeddedType embeddedType,

        @NotNull(message = "La posición inicial del PLU es obligatoria.")
        @Min(value = 0, message = "pluStart debe ser al menos 0.")
        @Max(value = 12, message = "pluStart no puede superar 12.")
        Short pluStart,

        @NotNull(message = "El largo del PLU es obligatorio.")
        @Min(value = 1, message = "pluLength debe ser al menos 1.")
        @Max(value = 6, message = "pluLength no puede superar 6.")
        Short pluLength,

        @NotNull(message = "La posición inicial del valor es obligatoria.")
        @Min(value = 0, message = "valueStart debe ser al menos 0.")
        @Max(value = 12, message = "valueStart no puede superar 12.")
        Short valueStart,

        @NotNull(message = "El largo del valor es obligatorio.")
        @Min(value = 1, message = "valueLength debe ser al menos 1.")
        @Max(value = 6, message = "valueLength no puede superar 6.")
        Short valueLength,

        @NotNull(message = "La cantidad de decimales es obligatoria.")
        @Min(value = 0, message = "valueDecimals debe ser al menos 0.")
        @Max(value = 3, message = "valueDecimals no puede superar 3.")
        Short valueDecimals
) {
}
