package cl.minimarket.pos.security.auth;

import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Servicio para consultar el usuario autenticado actual y evaluar permisos con alcance de local.
 *
 * <p>Provee la operación {@link #hasPermission(String, Long)} definida en el contrato del módulo
 * {@code security} (PLAN A.4): un usuario debe poseer el permiso y tener acceso al local indicado
 * (ya sea por {@code user_stores} o por el permiso {@link Permissions#SECURITY_STORE_ALL_ACCESS}).
 */
@Service
public class CurrentUserService {

    private final AuthQueryRepository queryRepository;

    /**
     * @param queryRepository repositorio de consultas de permisos y locales
     */
    public CurrentUserService(AuthQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    /**
     * Retorna el usuario autenticado actual o lanza 401 {@code UNAUTHENTICATED} si no hay sesión.
     *
     * @return usuario autenticado actual
     */
    public CurrentUser current() {
        return findCurrent()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Debe iniciar sesión para continuar."));
    }

    /**
     * Busca el usuario autenticado en el {@link SecurityContextHolder}.
     *
     * <p>Soporta tanto el principal {@link CurrentUser} emitido por la autenticación JWT como
     * principales de prueba ({@code user("1")} / {@code @WithMockUser}) que expongan id numérico.
     *
     * @return usuario actual o vacío si la request es anónima o no autenticada
     */
    public Optional<CurrentUser> findCurrent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof CurrentUser currentUser) {
            return Optional.of(currentUser);
        }
        Optional<Long> userId = CurrentUserId.of(auth);
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new CurrentUser(userId.get(), auth.getName(), TokenScope.FULL, authorities));
    }

    /**
     * Evalúa si el usuario autenticado tiene el permiso indicado para operar sobre {@code storeId}.
     *
     * @param permissionCode código de permiso de {@link Permissions}
     * @param storeId identificador del local; si es {@code null}, retorna {@code false}
     * @return {@code true} si tiene el permiso y acceso al local activo
     */
    public boolean hasPermission(String permissionCode, Long storeId) {
        if (permissionCode == null || storeId == null) {
            return false;
        }
        Optional<CurrentUser> maybeUser = findCurrent();
        if (maybeUser.isEmpty()) {
            return false;
        }
        CurrentUser user = maybeUser.get();
        if (!user.hasPermission(permissionCode)) {
            return false;
        }
        boolean allAccess = user.hasPermission(Permissions.SECURITY_STORE_ALL_ACCESS);
        return queryRepository.hasStoreAccess(user.userId(), storeId, allAccess);
    }

    /**
     * Evalúa si el usuario autenticado tiene el permiso indicado en el local activo del
     * {@link StoreContext} actual.
     *
     * @param permissionCode código de permiso de {@link Permissions}
     * @return {@code true} si hay un local activo en contexto, el usuario tiene el permiso y acceso al local
     */
    public boolean hasPermissionInCurrentStore(String permissionCode) {
        return StoreContext.current()
                .map(ctx -> hasPermission(permissionCode, ctx.storeId()))
                .orElse(false);
    }
}
