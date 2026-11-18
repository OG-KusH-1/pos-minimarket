package cl.minimarket.pos.security.roles;

import java.util.List;

/**
 * Respuesta de detalle de un rol ({@code API.md} F.3).
 */
public record RoleResponse(
        Long id,
        String code,
        String name,
        String description,
        boolean system,
        List<String> permissionCodes,
        long userCount
) {
}
