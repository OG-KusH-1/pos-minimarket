package cl.minimarket.pos.security.roles;

/**
 * Respuesta de catálogo de un permiso del sistema ({@code API.md} F.3).
 */
public record PermissionResponse(
        String code,
        String module,
        String description,
        boolean sensitive
) {

    /**
     * Convierte una entidad {@link PermissionEntity} a su DTO de respuesta.
     */
    public static PermissionResponse from(PermissionEntity entity) {
        return new PermissionResponse(
                entity.getCode(),
                entity.getModule(),
                entity.getDescription(),
                entity.isSensitive()
        );
    }
}
