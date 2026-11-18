package cl.minimarket.pos.security.roles;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de creación o actualización de un rol ({@code API.md} F.3).
 *
 * @param code código único del rol (inmutable al actualizar)
 * @param name nombre legible del rol
 * @param description descripción opcional
 */
public record RoleRequest(
        @NotBlank(message = "El código del rol es obligatorio.")
        @Pattern(regexp = "^[A-Z_]{3,30}$", message = "El código debe tener entre 3 y 30 letras mayúsculas o guiones bajos.")
        String code,

        @NotBlank(message = "El nombre del rol es obligatorio.")
        @Size(min = 2, max = 80, message = "El nombre debe tener entre 2 y 80 caracteres.")
        String name,

        @Size(max = 250, message = "La descripción no puede superar los 250 caracteres.")
        String description
) {
}
