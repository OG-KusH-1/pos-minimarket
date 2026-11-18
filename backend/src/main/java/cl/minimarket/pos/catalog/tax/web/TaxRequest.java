package cl.minimarket.pos.catalog.tax.web;

import cl.minimarket.pos.catalog.tax.domain.TaxType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Datos de entrada para crear o actualizar un impuesto ({@code API.md} F.3).
 */
public record TaxRequest(
        @NotBlank(message = "El código es obligatorio.")
        @Pattern(regexp = "^[A-Z_]{2,30}$",
                message = "El código debe tener entre 2 y 30 caracteres (solo mayúsculas y '_').")
        String code,

        @NotBlank(message = "El nombre es obligatorio.")
        @Size(min = 2, max = 100, message = "El nombre debe tener entre 2 y 100 caracteres.")
        String name,

        @NotNull(message = "El tipo de impuesto es obligatorio.")
        TaxType taxType,

        @NotNull(message = "La tasa es obligatoria.")
        @DecimalMin(value = "0", message = "La tasa debe ser mayor o igual a 0.")
        @DecimalMax(value = "100", message = "La tasa debe ser menor o igual a 100.")
        BigDecimal rate,

        @Size(max = 10, message = "El código SII no puede superar los 10 caracteres.")
        String siiCode,

        @NotNull(message = "La fecha de inicio de vigencia es obligatoria.")
        LocalDate validFrom,

        LocalDate validTo
) {
}
