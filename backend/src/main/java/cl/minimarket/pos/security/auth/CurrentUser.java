package cl.minimarket.pos.security.auth;

import cl.minimarket.pos.shared.context.UserIdentity;

import java.util.Objects;
import java.util.Set;

/**
 * Representación inmutable del usuario autenticado en el contexto de seguridad actual.
 *
 * <p>Implementa {@link UserIdentity} (L-03) para que {@code StoreContextFilter},
 * {@code IdempotencyFilter} y {@code CurrentUserAuditor} extraigan el id numérico sin acoplar
 * {@code shared} con {@code security}.
 *
 * @param userId identificador numérico del usuario en {@code users}
 * @param username nombre de usuario
 * @param scope alcance del token ({@link TokenScope#FULL} o {@link TokenScope#POS})
 * @param permissions conjunto inmutable de códigos de permiso efectivos del usuario
 */
public record CurrentUser(
        Long userId,
        String username,
        TokenScope scope,
        Set<String> permissions
) implements UserIdentity {

    /** Valida invariantes y normaliza el conjunto de permisos a inmutable. */
    public CurrentUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(username, "username");
        scope = scope == null ? TokenScope.FULL : scope;
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /**
     * Indica si el usuario autenticado posee el permiso indicado dentro del alcance del token.
     *
     * @param permissionCode código de permiso de {@code Permissions}
     * @return {@code true} si el permiso está presente
     */
    public boolean hasPermission(String permissionCode) {
        return permissionCode != null && permissions.contains(permissionCode);
    }
}
