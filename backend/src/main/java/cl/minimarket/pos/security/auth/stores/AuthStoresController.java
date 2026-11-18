package cl.minimarket.pos.security.auth.stores;

import cl.minimarket.pos.security.auth.AuthQueryRepository;
import cl.minimarket.pos.security.auth.CurrentUser;
import cl.minimarket.pos.security.auth.CurrentUserService;
import cl.minimarket.pos.security.auth.dto.StoreSummary;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador para consultar los locales accesibles por el usuario autenticado
 * ({@code GET /api/v1/auth/me/stores}, D.4 y F.2).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Autenticación y sesión del usuario actual")
public class AuthStoresController {

    private final CurrentUserService currentUserService;
    private final AuthQueryRepository authQueryRepository;

    /**
     * @param currentUserService servicio del usuario autenticado actual
     * @param authQueryRepository repositorio de consultas de permisos y locales
     */
    public AuthStoresController(
            CurrentUserService currentUserService,
            AuthQueryRepository authQueryRepository
    ) {
        this.currentUserService = currentUserService;
        this.authQueryRepository = authQueryRepository;
    }

    /**
     * Devuelve los locales activos asignados al usuario actual (o todos los locales activos si
     * posee {@link Permissions#SECURITY_STORE_ALL_ACCESS}). Primer paso tras el login (D.4).
     */
    @GetMapping("/me/stores")
    @GlobalEndpoint
    @Transactional(readOnly = true)
    @Operation(
            summary = "Listar locales accesibles del usuario actual",
            description = "Permiso: autenticado. Errores: UNAUTHENTICATED."
    )
    public List<StoreSummary> myStores() {
        CurrentUser current = currentUserService.current();
        boolean allAccess = authQueryRepository.findPermissionCodesByUserId(current.userId())
                .contains(Permissions.SECURITY_STORE_ALL_ACCESS);
        return authQueryRepository.findAccessibleStores(current.userId(), allAccess);
    }
}
