package cl.minimarket.pos.security.users;

import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.web.PageQuery;
import cl.minimarket.pos.shared.web.PageResponse;
import cl.minimarket.pos.shared.web.SortParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador REST global ([G]) para la gestión de usuarios, credenciales y sesiones
 * ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1/users")
@GlobalEndpoint
@Tag(name = "Security", description = "Gestión de usuarios, roles, permisos, límites y sesiones")
public class UserController {

    private static final SortParser SORT = SortParser.allowing("id", "username", "fullName", "active", "createdAt")
            .withDefault(Sort.by("id"));

    private final UserService userService;

    /**
     * @param userService servicio de casos de uso de usuarios
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** Lista usuarios paginados filtrando por texto, rol, local, estado activo o bloqueo. */
    @GetMapping
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_USER_READ)")
    @Operation(
            summary = "Listar usuarios",
            description = "Permiso: security:user:read. Errores: UNAUTHENTICATED, ACCESS_DENIED, VALIDATION_ERROR."
    )
    public PageResponse<UserSummary> listUsers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean locked,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(SORT.parse(sort));
        return PageResponse.of(userService.list(q, roleId, storeId, active, locked, pageable));
    }

    /** Obtiene el detalle de un usuario por identificador. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_USER_READ)")
    @Operation(
            summary = "Obtener usuario",
            description = "Permiso: security:user:read. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND."
    )
    public UserResponse getUser(@PathVariable Long id) {
        return userService.get(id);
    }

    /** Crea un nuevo usuario y devuelve su contraseña temporal de 16 caracteres. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_USER_CREATE)")
    @Operation(
            summary = "Crear usuario",
            description = "Permiso: security:user:create. Errores: UNAUTHENTICATED, ACCESS_DENIED, DUPLICATED_VALUE, RUT_INVALID, VALIDATION_ERROR."
    )
    public UserCreatedResponse createUser(@Valid @RequestBody UserRequest request) {
        return userService.create(request);
    }

    /** Actualiza los datos, roles y locales de un usuario existente. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_USER_UPDATE)")
    @Operation(
            summary = "Actualizar usuario",
            description = "Permiso: security:user:update. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND, DUPLICATED_VALUE, CONCURRENT_MODIFICATION, ENTITY_IN_USE, SEGREGATION_OF_DUTIES, RUT_INVALID, VALIDATION_ERROR."
    )
    public UserResponse updateUser(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return userService.update(id, request);
    }

    /** Desactiva un usuario y revoca sus sesiones activas. */
    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_USER_DISABLE)")
    @Operation(
            summary = "Desactivar usuario",
            description = "Permiso: security:user:disable. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND, ENTITY_IN_USE, SEGREGATION_OF_DUTIES."
    )
    public void disableUser(@PathVariable Long id) {
        userService.disable(id);
    }

    /** Reactiva un usuario desactivado. */
    @PostMapping("/{id}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_USER_DISABLE)")
    @Operation(
            summary = "Activar usuario",
            description = "Permiso: security:user:disable. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND, SEGREGATION_OF_DUTIES."
    )
    public void enableUser(@PathVariable Long id) {
        userService.enable(id);
    }

    /** Resetea la contraseña de un usuario generando una nueva clave temporal. */
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_USER_RESET_CREDENTIALS)")
    @Operation(
            summary = "Resetear contraseña de usuario",
            description = "Permiso: security:user:reset-credentials. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND."
    )
    public TemporaryPasswordResponse resetPassword(@PathVariable Long id) {
        return userService.resetPassword(id);
    }

    /** Resetea el PIN de un usuario y levanta el bloqueo del PIN. */
    @PostMapping("/{id}/reset-pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_USER_RESET_CREDENTIALS)")
    @Operation(
            summary = "Resetear PIN de usuario",
            description = "Permiso: security:user:reset-credentials. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND."
    )
    public void resetPin(@PathVariable Long id) {
        userService.resetPin(id);
    }

    /** Desbloquea la cuenta y el PIN de un usuario bloqueado por intentos fallidos. */
    @PostMapping("/{id}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_USER_RESET_CREDENTIALS)")
    @Operation(
            summary = "Desbloquear usuario",
            description = "Permiso: security:user:reset-credentials. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND."
    )
    public void unlockUser(@PathVariable Long id) {
        userService.unlock(id);
    }

    /** Lista las sesiones activas de un usuario. */
    @GetMapping("/{id}/sessions")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_SESSION_REVOKE)")
    @Operation(
            summary = "Listar sesiones activas de un usuario",
            description = "Permiso: security:session:revoke. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND."
    )
    public List<SessionResponse> listSessions(@PathVariable Long id) {
        return userService.listSessions(id);
    }

    /** Revoca todas las sesiones activas de un usuario y registra la auditoría correspondiente. */
    @DeleteMapping("/{id}/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_SESSION_REVOKE)")
    @Operation(
            summary = "Revocar sesiones de un usuario",
            description = "Permiso: security:session:revoke. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND."
    )
    public void revokeSessions(@PathVariable Long id) {
        userService.revokeSessions(id);
    }
}
