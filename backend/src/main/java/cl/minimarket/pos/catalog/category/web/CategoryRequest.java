package cl.minimarket.pos.catalog.category.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Datos de entrada para crear o actualizar una categoría ({@code API.md} F.3).
 *
 * @param name nombre de la categoría (2 a 100 caracteres)
 * @param code código opcional (hasta 20 caracteres)
 * @param parentId id de la categoría padre si es subcategoría; {@code null} si es raíz
 */
public record CategoryRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(min = 2, max = 100, message = "El nombre debe tener entre 2 y 100 caracteres.")
        String name,
        @Size(max = 20, message = "El código no puede superar los 20 caracteres.")
        String code,
        Long parentId
) {
}
