package cl.minimarket.pos.security.users;

import java.util.List;

/**
 * Resumen de usuario para listados paginados ({@code API.md} F.3).
 */
public record UserSummary(
        Long id,
        String username,
        String fullName,
        boolean active,
        boolean locked,
        List<String> roleCodes
) {
}
