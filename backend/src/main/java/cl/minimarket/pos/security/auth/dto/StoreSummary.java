package cl.minimarket.pos.security.auth.dto;

/**
 * Resumen de un local accesible para el usuario autenticado (API.md F.3).
 *
 * @param id identificador del local
 * @param code código corto del local
 * @param name nombre del local
 * @param active indica si el local está activo
 */
public record StoreSummary(
        Long id,
        String code,
        String name,
        boolean active
) {
}
