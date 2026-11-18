package cl.minimarket.pos.security.users;

/**
 * Resumen de un rol asignado a un usuario ({@code API.md} F.3).
 */
public record RoleSummary(
        Long id,
        String code,
        String name
) {
}
