package cl.minimarket.pos.security.auth;

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * Evaluador de permisos por local para expresiones SpEL {@code hasPermission(...)}.
 *
 * <p>Permite usar {@code @PreAuthorize("hasPermission(#storeId, 'STORE', 'inventory:stock:read')")}
 * o {@code @PreAuthorize("hasPermission(#storeId, 'inventory:stock:read')")}, delegando en
 * {@link CurrentUserService#hasPermission(String, Long)}.
 */
@Component
public class StorePermissionEvaluator implements PermissionEvaluator {

    private final CurrentUserService currentUserService;

    /**
     * @param currentUserService servicio de usuario actual y evaluación por local
     */
    public StorePermissionEvaluator(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || !authentication.isAuthenticated() || permission == null) {
            return false;
        }
        if (targetDomainObject instanceof Long storeId) {
            return currentUserService.hasPermission(permission.toString(), storeId);
        }
        if (targetDomainObject instanceof Number storeNumber) {
            return currentUserService.hasPermission(permission.toString(), storeNumber.longValue());
        }
        return false;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (authentication == null || !authentication.isAuthenticated() || permission == null) {
            return false;
        }
        if (targetId instanceof Long storeId) {
            return currentUserService.hasPermission(permission.toString(), storeId);
        }
        if (targetId instanceof Number storeNumber) {
            return currentUserService.hasPermission(permission.toString(), storeNumber.longValue());
        }
        return false;
    }
}
