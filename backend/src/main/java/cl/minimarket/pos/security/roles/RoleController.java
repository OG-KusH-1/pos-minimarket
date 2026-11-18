package cl.minimarket.pos.security.roles;

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
 * Controlador REST global ([G]) para la gestión de roles, permisos y límites por rol
 * ({@code API.md} F.2).
 */
@RestController
@RequestMapping("/api/v1")
@GlobalEndpoint
@Tag(name = "Security", description = "Gestión de usuarios, roles, permisos, límites y sesiones")
public class RoleController {

    private static final SortParser SORT = SortParser.allowing("id", "code", "name", "system")
            .withDefault(Sort.by("id"));

    private final RoleService roleService;

    /**
     * @param roleService servicio de casos de uso de roles y límites
     */
    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /** Lista los roles de forma paginada. */
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_ROLE_READ)")
    @Operation(
            summary = "Listar roles",
            description = "Permiso: security:role:read. Errores: UNAUTHENTICATED, ACCESS_DENIED, VALIDATION_ERROR."
    )
    public PageResponse<RoleResponse> listRoles(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(SORT.parse(sort));
        return PageResponse.of(roleService.list(pageable));
    }

    /** Obtiene el detalle de un rol por identificador. */
    @GetMapping("/roles/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_ROLE_READ)")
    @Operation(
            summary = "Obtener rol",
            description = "Permiso: security:role:read. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND."
    )
    public RoleResponse getRole(@PathVariable Long id) {
        return roleService.get(id);
    }

    /** Crea un rol nuevo y siembra todas sus filas en {@code role_limits} con valor 0. */
    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_ROLE_MANAGE)")
    @Operation(
            summary = "Crear rol",
            description = "Permiso: security:role:manage. Errores: UNAUTHENTICATED, ACCESS_DENIED, DUPLICATED_VALUE, VALIDATION_ERROR."
    )
    public RoleResponse createRole(@Valid @RequestBody RoleRequest request) {
        return roleService.create(request);
    }

    /** Actualiza nombre y descripción de un rol (su código es inmutable). */
    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_ROLE_MANAGE)")
    @Operation(
            summary = "Actualizar rol",
            description = "Permiso: security:role:manage. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND, VALIDATION_ERROR."
    )
    public RoleResponse updateRole(@PathVariable Long id, @Valid @RequestBody RoleRequest request) {
        return roleService.update(id, request);
    }

    /** Elimina un rol si no es de sistema ni tiene usuarios asignados. */
    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_ROLE_MANAGE)")
    @Operation(
            summary = "Eliminar rol",
            description = "Permiso: security:role:manage. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND, ENTITY_IN_USE."
    )
    public void deleteRole(@PathVariable Long id) {
        roleService.delete(id);
    }

    /** Reemplaza el conjunto de permisos asignados a un rol y audita el cambio. */
    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_ROLE_MANAGE)")
    @Operation(
            summary = "Actualizar permisos de un rol",
            description = "Permiso: security:role:manage. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND, ENTITY_IN_USE, VALIDATION_ERROR."
    )
    public RoleResponse replacePermissions(
            @PathVariable Long id,
            @Valid @RequestBody RolePermissionsRequest request
    ) {
        return roleService.replacePermissions(id, request);
    }

    /** Lista el catálogo completo de permisos disponibles en el sistema. */
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_ROLE_READ)")
    @Operation(
            summary = "Listar catálogo de permisos",
            description = "Permiso: security:role:read. Errores: UNAUTHENTICATED, ACCESS_DENIED."
    )
    public List<PermissionResponse> listPermissions() {
        return roleService.listPermissions();
    }

    /** Obtiene los límites numéricos configurados para un rol. */
    @GetMapping("/roles/{id}/limits")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).SECURITY_ROLE_READ)")
    @Operation(
            summary = "Obtener límites de un rol",
            description = "Permiso: security:role:read. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND."
    )
    public List<RoleLimitResponse> getRoleLimits(@PathVariable Long id) {
        return roleService.getLimits(id);
    }

    /** Actualiza los límites numéricos de un rol y audita el cambio. */
    @PutMapping("/roles/{id}/limits")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).CONFIG_ROLE_LIMIT_MANAGE)")
    @Operation(
            summary = "Actualizar límites de un rol",
            description = "Permiso: config:role-limit:manage. Errores: UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND, VALIDATION_ERROR."
    )
    public List<RoleLimitResponse> updateRoleLimits(
            @PathVariable Long id,
            @Valid @RequestBody RoleLimitsRequest request
    ) {
        return roleService.updateLimits(id, request);
    }
}
