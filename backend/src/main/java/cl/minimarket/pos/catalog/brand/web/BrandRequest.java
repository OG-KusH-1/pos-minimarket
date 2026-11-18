package cl.minimarket.pos.catalog.brand.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Datos de entrada para crear o actualizar una marca ({@code API.md} F.3).
 *
 * @param name nombre de la marca (1 a 100 caracteres)
 */
public record BrandRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(min = 1, max = 100, message = "El nombre debe tener entre 1 y 100 caracteres.")
        String name
) {
}
