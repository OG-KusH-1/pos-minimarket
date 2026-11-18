package cl.minimarket.pos.security.roles;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.security.Permissions;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Casos de uso de administración de roles, permisos y límites numéricos ({@code API.md} F.2).
 */
@Service
public class RoleService {

    /** Código reservado del rol administrador base del sistema. */
    public static final String ADMIN_ROLE_CODE = "ADMIN";

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final Set<String> ESSENTIAL_ADMIN_PERMISSIONS = Set.of(
            Permissions.SECURITY_ROLE_READ,
            Permissions.SECURITY_ROLE_MANAGE,
            Permissions.SECURITY_USER_READ,
            Permissions.SECURITY_USER_CREATE,
            Permissions.SECURITY_USER_UPDATE,
            Permissions.SECURITY_USER_DISABLE,
            Permissions.SECURITY_STORE_ALL_ACCESS
    );

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleLimitRepository roleLimitRepository;
    private final RoleQueryRepository roleQueryRepository;
    private final AuditService auditService;

    /**
     * @param roleRepository repositorio JPA de roles
     * @param permissionRepository repositorio JPA del catálogo de permisos
     * @param roleLimitRepository repositorio JPA de límites por rol
     * @param roleQueryRepository repositorio JDBC de permisos y conteos por rol
     * @param auditService servicio de bitácora inmutable
     */
    public RoleService(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            RoleLimitRepository roleLimitRepository,
            RoleQueryRepository roleQueryRepository,
            AuditService auditService
    ) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.roleLimitRepository = roleLimitRepository;
        this.roleQueryRepository = roleQueryRepository;
        this.auditService = auditService;
    }

    /**
     * Lista los roles paginados junto con sus permisos y cantidad de usuarios asignados.
     */
    @Transactional(readOnly = true)
    public Page<RoleResponse> list(Pageable pageable) {
        Page<RoleEntity> page = roleRepository.findAll(pageable);
        List<Long> roleIds = page.getContent().stream().map(RoleEntity::getId).toList();
        Map<Long, List<String>> permsByRole = roleQueryRepository.findPermissionCodesByRoleIds(roleIds);
        Map<Long, Long> countsByRole = roleQueryRepository.countUsersByRoleIds(roleIds);

        return page.map(role -> new RoleResponse(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.isSystem(),
                permsByRole.getOrDefault(role.getId(), List.of()),
                countsByRole.getOrDefault(role.getId(), 0L)
        ));
    }

    /**
     * Obtiene el detalle de un rol por su identificador.
     */
    @Transactional(readOnly = true)
    public RoleResponse get(Long id) {
        RoleEntity role = findRoleOrThrow(id);
        return toResponse(role);
    }

    /**
     * Crea un rol nuevo y siembra todas sus filas en {@code role_limits} con valor {@code 0}
     * (semántica <em>fail-closed</em>, {@code ROLES_PERMISOS.md} D.3).
     */
    @Transactional
    public RoleResponse create(RoleRequest request) {
        String code = request.code().trim();
        if (roleRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un rol con el código " + code + ".");
        }

        RoleEntity saved = roleRepository.save(new RoleEntity(
                code,
                request.name().trim(),
                normalizeOptional(request.description())
        ));

        List<RoleLimitEntity> initialLimits = new ArrayList<>(RoleLimitCode.values().length);
        for (RoleLimitCode limitCode : RoleLimitCode.values()) {
            initialLimits.add(new RoleLimitEntity(saved.getId(), limitCode, BigDecimal.ZERO));
        }
        roleLimitRepository.saveAll(initialLimits);

        return new RoleResponse(
                saved.getId(),
                saved.getCode(),
                saved.getName(),
                saved.getDescription(),
                saved.isSystem(),
                List.of(),
                0L
        );
    }

    /**
     * Actualiza nombre y descripción de un rol existente ({@code code} es inmutable).
     */
    @Transactional
    public RoleResponse update(Long id, RoleRequest request) {
        RoleEntity role = findRoleOrThrow(id);
        if (!role.getCode().equals(request.code().trim())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El código del rol es inmutable.",
                    Map.of(),
                    List.of(new FieldErrorDetail("code", "IMMUTABLE", "El código de un rol existente no se puede modificar."))
            );
        }

        role.setName(request.name().trim());
        role.setDescription(normalizeOptional(request.description()));
        RoleEntity saved = roleRepository.save(role);
        return toResponse(saved);
    }

    /**
     * Elimina un rol no perteneciente al sistema y sin usuarios asignados.
     */
    @Transactional
    public void delete(Long id) {
        RoleEntity role = findRoleOrThrow(id);
        if (role.isSystem()) {
            throw new BusinessException(ErrorCode.ENTITY_IN_USE,
                    "No se puede eliminar un rol base del sistema.");
        }
        if (roleQueryRepository.countUsersByRoleId(id) > 0) {
            throw new BusinessException(ErrorCode.ENTITY_IN_USE,
                    "No se puede eliminar el rol porque tiene usuarios asignados.");
        }
        roleRepository.delete(role);
    }

    /**
     * Reemplaza los permisos de un rol validando existencia y protegiendo al último {@code ADMIN} activo.
     */
    @Transactional
    public RoleResponse replacePermissions(Long id, RolePermissionsRequest request) {
        RoleEntity role = findRoleOrThrow(id);

        Set<String> requestedCodes = new LinkedHashSet<>();
        for (String raw : request.permissionCodes()) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                requestedCodes.add(trimmed);
            }
        }

        List<PermissionEntity> permissions = requestedCodes.isEmpty()
                ? List.of()
                : permissionRepository.findByCodeIn(requestedCodes);

        if (permissions.size() != requestedCodes.size()) {
            Set<String> foundCodes = new HashSet<>();
            for (PermissionEntity p : permissions) {
                foundCodes.add(p.getCode());
            }
            List<String> missing = requestedCodes.stream()
                    .filter(c -> !foundCodes.contains(c))
                    .toList();
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Existen códigos de permiso desconocidos: " + String.join(", ", missing),
                    Map.of("missingPermissions", missing),
                    List.of(new FieldErrorDetail("permissionCodes", "UNKNOWN_PERMISSION",
                            "Permisos no encontrados: " + String.join(", ", missing)))
            );
        }

        if (ADMIN_ROLE_CODE.equals(role.getCode()) && !requestedCodes.containsAll(ESSENTIAL_ADMIN_PERMISSIONS)) {
            throw new BusinessException(ErrorCode.ENTITY_IN_USE,
                    "No se pueden quitar permisos esenciales de administración al rol ADMIN.");
        }

        List<String> oldPermissions = roleQueryRepository.findPermissionCodesByRoleId(id);
        List<Long> permissionIds = permissions.stream().map(PermissionEntity::getId).toList();
        roleQueryRepository.replaceRolePermissions(id, permissionIds);
        List<String> newPermissions = permissions.stream()
                .map(PermissionEntity::getCode)
                .sorted()
                .toList();

        auditService.record(new AuditEvent(
                "ROLE_PERMISSIONS_CHANGED",
                "role",
                String.valueOf(id),
                Map.of("roleCode", role.getCode(), "permissions", oldPermissions),
                Map.of("roleCode", role.getCode(), "permissions", newPermissions),
                null,
                null
        ));

        return new RoleResponse(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.isSystem(),
                newPermissions,
                roleQueryRepository.countUsersByRoleId(id)
        );
    }

    /**
     * Lista el catálogo completo de permisos de solo lectura.
     */
    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions() {
        return permissionRepository.findAllByOrderByModuleAscCodeAsc().stream()
                .map(PermissionResponse::from)
                .toList();
    }

    /**
     * Obtiene todos los límites configurados para un rol (completando ausentes con {@code 0} fail-closed).
     */
    @Transactional(readOnly = true)
    public List<RoleLimitResponse> getLimits(Long roleId) {
        findRoleOrThrow(roleId);
        return buildOrderedLimitResponses(roleId);
    }

    /**
     * Actualiza los límites indicados para un rol y registra el evento {@code ROLE_LIMIT_CHANGED}.
     */
    @Transactional
    public List<RoleLimitResponse> updateLimits(Long roleId, RoleLimitsRequest request) {
        RoleEntity role = findRoleOrThrow(roleId);
        validateLimitItems(request.limits());

        List<RoleLimitEntity> existingEntities = roleLimitRepository.findByRoleIdOrderByLimitCodeAsc(roleId);
        Map<RoleLimitCode, RoleLimitEntity> byCode = new EnumMap<>(RoleLimitCode.class);
        for (RoleLimitEntity entity : existingEntities) {
            byCode.put(entity.getLimitCode(), entity);
        }

        Map<String, BigDecimal> oldSnapshot = snapshotLimits(byCode);

        List<RoleLimitEntity> toSave = new ArrayList<>();
        for (RoleLimitItemRequest item : request.limits()) {
            RoleLimitEntity entity = byCode.get(item.code());
            if (entity == null) {
                entity = new RoleLimitEntity(roleId, item.code(), item.value());
                byCode.put(item.code(), entity);
            } else {
                entity.setValue(item.value());
            }
            toSave.add(entity);
        }
        roleLimitRepository.saveAll(toSave);

        Map<String, BigDecimal> newSnapshot = snapshotLimits(byCode);
        auditService.record(new AuditEvent(
                "ROLE_LIMIT_CHANGED",
                "role",
                String.valueOf(roleId),
                Map.of("roleCode", role.getCode(), "limits", oldSnapshot),
                Map.of("roleCode", role.getCode(), "limits", newSnapshot),
                null,
                null
        ));

        return buildOrderedLimitResponses(byCode);
    }

    private void validateLimitItems(List<RoleLimitItemRequest> items) {
        Set<RoleLimitCode> seen = new HashSet<>();
        for (RoleLimitItemRequest item : items) {
            if (!seen.add(item.code())) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Código de límite duplicado: " + item.code(),
                        Map.of(),
                        List.of(new FieldErrorDetail("limits", "DUPLICATED_CODE",
                                "Código duplicado en la solicitud: " + item.code()))
                );
            }
            if (item.value() != null) {
                if (item.value().compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "El valor del límite debe ser mayor o igual a 0.",
                            Map.of(),
                            List.of(new FieldErrorDetail("limits", "OUT_OF_RANGE",
                                    "Valor negativo no permitido para " + item.code()))
                    );
                }
                if (isPercentageLimit(item.code()) && item.value().compareTo(ONE_HUNDRED) > 0) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "El límite porcentual no puede superar 100.",
                            Map.of(),
                            List.of(new FieldErrorDetail("limits", "OUT_OF_RANGE",
                                    "El porcentaje para " + item.code() + " debe estar entre 0 y 100."))
                    );
                }
            }
        }
    }

    private static boolean isPercentageLimit(RoleLimitCode code) {
        return code == RoleLimitCode.DISCOUNT_ITEM_MAX_PCT || code == RoleLimitCode.DISCOUNT_TOTAL_MAX_PCT;
    }

    private List<RoleLimitResponse> buildOrderedLimitResponses(Long roleId) {
        Map<RoleLimitCode, RoleLimitEntity> byCode = new EnumMap<>(RoleLimitCode.class);
        for (RoleLimitEntity entity : roleLimitRepository.findByRoleIdOrderByLimitCodeAsc(roleId)) {
            byCode.put(entity.getLimitCode(), entity);
        }
        return buildOrderedLimitResponses(byCode);
    }

    private List<RoleLimitResponse> buildOrderedLimitResponses(Map<RoleLimitCode, RoleLimitEntity> byCode) {
        List<RoleLimitResponse> responses = new ArrayList<>(RoleLimitCode.values().length);
        for (RoleLimitCode code : RoleLimitCode.values()) {
            RoleLimitEntity entity = byCode.get(code);
            BigDecimal value = entity != null ? entity.getValue() : BigDecimal.ZERO;
            responses.add(new RoleLimitResponse(code, value));
        }
        return responses;
    }

    private Map<String, BigDecimal> snapshotLimits(Map<RoleLimitCode, RoleLimitEntity> byCode) {
        Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
        for (RoleLimitCode code : RoleLimitCode.values()) {
            RoleLimitEntity entity = byCode.get(code);
            snapshot.put(code.name(), entity != null ? entity.getValue() : BigDecimal.ZERO);
        }
        return snapshot;
    }

    private RoleEntity findRoleOrThrow(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Rol no encontrado."));
    }

    private RoleResponse toResponse(RoleEntity role) {
        return new RoleResponse(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.isSystem(),
                roleQueryRepository.findPermissionCodesByRoleId(role.getId()),
                roleQueryRepository.countUsersByRoleId(role.getId())
        );
    }

    private static String normalizeOptional(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
