package cl.minimarket.pos.security.roles;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Solicitud para reemplazar el conjunto de permisos asignados a un rol ({@code API.md} F.3).
 *
 * @param permissionCodes lista de códigos de permiso existentes en el catálogo
 */
public record RolePermissionsRequest(
        @NotNull(message = "La lista de permisos es obligatoria.")
        List<@NotNull(message = "El código de permiso no puede ser nulo.") String> permissionCodes
) {
}
