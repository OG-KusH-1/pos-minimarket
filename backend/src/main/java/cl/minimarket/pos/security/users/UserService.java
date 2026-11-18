package cl.minimarket.pos.security.users;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.security.auth.AuthQueryRepository;
import cl.minimarket.pos.security.auth.CurrentUser;
import cl.minimarket.pos.security.auth.CurrentUserService;
import cl.minimarket.pos.security.auth.dto.StoreSummary;
import cl.minimarket.pos.security.roles.RoleEntity;
import cl.minimarket.pos.security.roles.RoleRepository;
import cl.minimarket.pos.security.roles.RoleService;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.rut.Rut;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Casos de uso de administración de usuarios, anti-escalada ({@code ROLES_PERMISOS.md} D.4),
 * reset de credenciales y revocación de sesiones ({@code API.md} F.2).
 */
@Service
public class UserService {

    private static final int TEMP_PASSWORD_LENGTH = 16;
    private static final String TEMP_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String TEMP_DIGITS = "23456789";
    private static final String TEMP_ALPHABET = TEMP_LETTERS + TEMP_DIGITS;

    private final SecureRandom secureRandom = new SecureRandom();
    private final UserAccountRepository userRepository;
    private final UserQueryRepository userQueryRepository;
    private final RoleRepository roleRepository;
    private final AuthQueryRepository authQueryRepository;
    private final StoreApi storeApi;
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final BusinessClock clock;

    /**
     * Constructor principal del servicio de administración de usuarios.
     */
    public UserService(
            UserAccountRepository userRepository,
            UserQueryRepository userQueryRepository,
            RoleRepository roleRepository,
            AuthQueryRepository authQueryRepository,
            StoreApi storeApi,
            CurrentUserService currentUserService,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            BusinessClock clock
    ) {
        this.userRepository = userRepository;
        this.userQueryRepository = userQueryRepository;
        this.roleRepository = roleRepository;
        this.authQueryRepository = authQueryRepository;
        this.storeApi = storeApi;
        this.currentUserService = currentUserService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Lista usuarios paginados con filtros opcionales. Si el actor no posee
     * {@code security:store:all-access}, solo ve usuarios asignados a sus propios locales.
     */
    @Transactional(readOnly = true)
    public Page<UserSummary> list(
            String q,
            Long roleId,
            Long storeId,
            Boolean active,
            Boolean locked,
            Pageable pageable
    ) {
        CurrentUser actor = currentUserService.current();
        Set<Long> visibleStores = actor.hasPermission(Permissions.SECURITY_STORE_ALL_ACCESS)
                ? null
                : userQueryRepository.findAssignedStoreIds(actor.userId());

        return userQueryRepository.searchUsers(
                q, roleId, storeId, active, locked, visibleStores, clock.now(), pageable);
    }

    /**
     * Obtiene el detalle completo de un usuario validando visibilidad por local.
     */
    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        CurrentUser actor = currentUserService.current();
        UserAccount user = findUserOrThrow(id);
        if (!actor.hasPermission(Permissions.SECURITY_STORE_ALL_ACCESS)) {
            Set<Long> actorStores = userQueryRepository.findAssignedStoreIds(actor.userId());
            Set<Long> targetStores = userQueryRepository.findAssignedStoreIds(id);
            boolean sharesStore = targetStores.stream().anyMatch(actorStores::contains);
            if (!sharesStore) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED,
                        "No tiene permiso para consultar usuarios fuera de sus locales asignados.");
            }
        }
        return buildUserResponse(user);
    }

    /**
     * Crea un usuario nuevo con contraseña temporal de 16 caracteres, aplicando validaciones de
     * unicidad, RUT, locales y control anti-escalada (D.4).
     */
    @Transactional
    public UserCreatedResponse create(UserRequest request) {
        CurrentUser actor = currentUserService.current();
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        String email = normalizeEmail(request.email());
        String rut = normalizeRut(request.rut());

        validateUniquenessForCreate(username, email, rut);

        Set<Long> roleIds = new LinkedHashSet<>(request.roleIds());
        List<RoleEntity> roles = resolveRolesOrThrow(roleIds);
        Set<String> targetPermissions = userQueryRepository.findPermissionCodesByRoleIds(roleIds);

        Set<Long> storeIds = new LinkedHashSet<>(request.storeIds());
        validateStores(storeIds, request.defaultStoreId(), targetPermissions);
        assertCanManageTargetState(actor, targetPermissions, storeIds);

        String tempPassword = generateTemporaryPassword();
        Instant now = clock.now();
        UserAccount saved = userRepository.save(new UserAccount(
                username,
                request.fullName().trim(),
                email,
                rut,
                passwordEncoder.encode(tempPassword),
                now
        ));

        userQueryRepository.replaceUserRoles(saved.getId(), roleIds);
        userQueryRepository.replaceUserStores(saved.getId(), storeIds, request.defaultStoreId(), now);

        UserResponse response = buildUserResponse(saved, roles, storeIds, request.defaultStoreId());
        auditService.record(new AuditEvent(
                "USER_CREATED",
                "user",
                String.valueOf(saved.getId()),
                null,
                snapshotUser(saved, roles.stream().map(RoleEntity::getCode).toList(), storeIds, request.defaultStoreId()),
                null,
                null
        ));

        return new UserCreatedResponse(response, tempPassword, true);
    }

    /**
     * Actualiza un usuario existente, sus roles y locales, validando versión optimista,
     * segregación de funciones (nadie edita su propio registro), anti-escalada y protección del
     * último {@code ADMIN} activo.
     */
    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        CurrentUser actor = currentUserService.current();
        assertNotSelf(actor.userId(), id, "No puede editar su propio registro de usuario.");

        if (request.version() == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "La versión del usuario es obligatoria para actualizar.",
                    Map.of(),
                    List.of(new FieldErrorDetail("version", "REQUIRED", "Debe enviar la versión vigente del usuario."))
            );
        }

        UserAccount user = findUserOrThrow(id);
        if (!request.version().equals(user.getVersion())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "El usuario fue modificado por otra operación.");
        }

        List<RoleSummary> oldRoles = userQueryRepository.findRolesByUserId(id);
        Set<Long> oldRoleIds = oldRoles.stream().map(RoleSummary::id).collect(java.util.stream.Collectors.toSet());
        Set<String> oldPermissions = userQueryRepository.findPermissionCodesByRoleIds(oldRoleIds);
        Set<Long> oldStores = userQueryRepository.findAssignedStoreIds(id);
        Long oldDefaultStoreId = authQueryRepository.findDefaultStoreId(id).orElse(null);

        assertCanManageTargetState(actor, oldPermissions, oldStores);

        String username = request.username().trim().toLowerCase(Locale.ROOT);
        String email = normalizeEmail(request.email());
        String rut = normalizeRut(request.rut());
        validateUniquenessForUpdate(id, username, email, rut);

        Set<Long> newRoleIds = new LinkedHashSet<>(request.roleIds());
        List<RoleEntity> newRoles = resolveRolesOrThrow(newRoleIds);
        Set<String> newPermissions = userQueryRepository.findPermissionCodesByRoleIds(newRoleIds);

        Set<Long> newStoreIds = new LinkedHashSet<>(request.storeIds());
        validateStores(newStoreIds, request.defaultStoreId(), newPermissions);
        assertCanManageTargetState(actor, newPermissions, newStoreIds);

        boolean hadAdmin = oldRoles.stream().anyMatch(r -> RoleService.ADMIN_ROLE_CODE.equals(r.code()));
        boolean keepsAdmin = newRoles.stream().anyMatch(r -> RoleService.ADMIN_ROLE_CODE.equals(r.getCode()));
        if (user.isActive() && hadAdmin && !keepsAdmin) {
            assertNotLastActiveAdmin();
        }

        Map<String, Object> oldSnapshot = snapshotUser(
                user, oldRoles.stream().map(RoleSummary::code).toList(), oldStores, oldDefaultStoreId);

        user.setUsername(username);
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setRut(rut);
        UserAccount saved = userRepository.save(user);

        Instant now = clock.now();
        userQueryRepository.replaceUserRoles(id, newRoleIds);
        userQueryRepository.replaceUserStores(id, newStoreIds, request.defaultStoreId(), now);

        auditService.record(new AuditEvent(
                "USER_UPDATED",
                "user",
                String.valueOf(id),
                oldSnapshot,
                snapshotUser(saved, newRoles.stream().map(RoleEntity::getCode).toList(), newStoreIds, request.defaultStoreId()),
                null,
                null
        ));

        return buildUserResponse(saved, newRoles, newStoreIds, request.defaultStoreId());
    }

    /**
     * Desactiva un usuario y revoca todas sus sesiones activas, impidiendo desactivar al propio
     * actor o al último {@code ADMIN} activo.
     */
    @Transactional
    public void disable(Long id) {
        CurrentUser actor = currentUserService.current();
        assertNotSelf(actor.userId(), id, "No puede desactivar su propio usuario.");

        UserAccount user = findUserOrThrow(id);
        assertCanManageExistingUser(actor, id);

        List<RoleSummary> roles = userQueryRepository.findRolesByUserId(id);
        boolean isAdmin = roles.stream().anyMatch(r -> RoleService.ADMIN_ROLE_CODE.equals(r.code()));
        if (user.isActive() && isAdmin) {
            assertNotLastActiveAdmin();
        }

        Instant now = clock.now();
        boolean wasActive = user.isActive();
        user.setActive(false);
        userRepository.save(user);
        userQueryRepository.revokeAllActiveSessions(id, now, "USER_DISABLED");

        if (wasActive) {
            auditService.record(new AuditEvent(
                    "USER_DISABLED",
                    "user",
                    String.valueOf(id),
                    Map.of("active", true),
                    Map.of("active", false),
                    null,
                    null
            ));
        }
    }

    /**
     * Reactiva un usuario desactivado validando segregación de funciones y anti-escalada.
     */
    @Transactional
    public void enable(Long id) {
        CurrentUser actor = currentUserService.current();
        assertNotSelf(actor.userId(), id, "No puede modificar su propio estado.");

        UserAccount user = findUserOrThrow(id);
        assertCanManageExistingUser(actor, id);

        boolean wasActive = user.isActive();
        user.setActive(true);
        userRepository.save(user);

        if (!wasActive) {
            auditService.record(new AuditEvent(
                    "USER_UPDATED",
                    "user",
                    String.valueOf(id),
                    Map.of("active", false),
                    Map.of("active", true),
                    null,
                    null
            ));
        }
    }

    /**
     * Resetea la contraseña de un usuario generando una nueva clave temporal de 16 caracteres,
     * levantando bloqueos de contraseña y revocando sesiones activas.
     */
    @Transactional
    public TemporaryPasswordResponse resetPassword(Long id) {
        CurrentUser actor = currentUserService.current();
        UserAccount user = findUserOrThrow(id);
        assertCanManageExistingUser(actor, id);

        String tempPassword = generateTemporaryPassword();
        Instant now = clock.now();
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        user.setPasswordChangedAt(now);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        userQueryRepository.revokeAllActiveSessions(id, now, "ADMIN");

        auditService.record(new AuditEvent(
                "USER_CREDENTIALS_RESET",
                "user",
                String.valueOf(id),
                null,
                Map.of("operation", "reset-password"),
                null,
                null
        ));

        return new TemporaryPasswordResponse(tempPassword, true);
    }

    /**
     * Resetea el PIN de un usuario y levanta su bloqueo de PIN.
     */
    @Transactional
    public void resetPin(Long id) {
        CurrentUser actor = currentUserService.current();
        UserAccount user = findUserOrThrow(id);
        assertCanManageExistingUser(actor, id);

        user.setPinHash(null);
        user.setPinFailedAttempts(0);
        user.setPinLocked(false);
        user.setPinLockedUntil(null);
        userRepository.save(user);

        auditService.record(new AuditEvent(
                "USER_CREDENTIALS_RESET",
                "user",
                String.valueOf(id),
                null,
                Map.of("operation", "reset-pin"),
                null,
                null
        ));
    }

    /**
     * Desbloquea la cuenta y el PIN de un usuario bloqueado por intentos fallidos.
     */
    @Transactional
    public void unlock(Long id) {
        CurrentUser actor = currentUserService.current();
        UserAccount user = findUserOrThrow(id);
        assertCanManageExistingUser(actor, id);

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setPinFailedAttempts(0);
        user.setPinLocked(false);
        user.setPinLockedUntil(null);
        userRepository.save(user);

        auditService.record(new AuditEvent(
                "USER_CREDENTIALS_RESET",
                "user",
                String.valueOf(id),
                null,
                Map.of("operation", "unlock"),
                null,
                null
        ));
    }

    /**
     * Lista las sesiones activas (refresh tokens vigentes no revocados) de un usuario.
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> listSessions(Long id) {
        CurrentUser actor = currentUserService.current();
        findUserOrThrow(id);
        assertCanManageExistingUser(actor, id);
        return userQueryRepository.findActiveSessionsByUserId(id, clock.now());
    }

    /**
     * Revoca todas las sesiones activas de un usuario y registra {@code SESSIONS_REVOKED}.
     */
    @Transactional
    public void revokeSessions(Long id) {
        CurrentUser actor = currentUserService.current();
        findUserOrThrow(id);
        assertCanManageExistingUser(actor, id);

        int revokedCount = userQueryRepository.revokeAllActiveSessions(id, clock.now(), "ADMIN");
        auditService.record(new AuditEvent(
                "SESSIONS_REVOKED",
                "user",
                String.valueOf(id),
                null,
                Map.of("userId", id, "revokedCount", revokedCount),
                null,
                null
        ));
    }

    private void assertNotSelf(Long actorId, Long targetId, String message) {
        if (actorId.equals(targetId)) {
            throw new BusinessException(ErrorCode.SEGREGATION_OF_DUTIES, message);
        }
    }

    private void assertCanManageExistingUser(CurrentUser actor, Long targetUserId) {
        if (actor.hasPermission(Permissions.SECURITY_STORE_ALL_ACCESS)) {
            return;
        }
        List<RoleSummary> roles = userQueryRepository.findRolesByUserId(targetUserId);
        Set<Long> roleIds = roles.stream().map(RoleSummary::id).collect(java.util.stream.Collectors.toSet());
        Set<String> targetPermissions = userQueryRepository.findPermissionCodesByRoleIds(roleIds);
        Set<Long> targetStores = userQueryRepository.findAssignedStoreIds(targetUserId);
        assertCanManageTargetState(actor, targetPermissions, targetStores);
    }

    private void assertCanManageTargetState(
            CurrentUser actor,
            Set<String> targetPermissions,
            Set<Long> targetStoreIds
    ) {
        if (actor.hasPermission(Permissions.SECURITY_STORE_ALL_ACCESS)) {
            return;
        }
        if (!actor.permissions().containsAll(targetPermissions)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "No puede gestionar usuarios con permisos superiores a los suyos.");
        }
        Set<Long> actorStores = userQueryRepository.findAssignedStoreIds(actor.userId());
        if (!actorStores.containsAll(targetStoreIds)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "No puede asignar o gestionar locales fuera de su propio alcance.");
        }
    }

    private void assertNotLastActiveAdmin() {
        long activeAdmins = userQueryRepository.countActiveUsersWithRoleCode(RoleService.ADMIN_ROLE_CODE);
        if (activeAdmins <= 1) {
            throw new BusinessException(ErrorCode.ENTITY_IN_USE,
                    "No se puede desactivar ni quitar el rol al último administrador activo.");
        }
    }

    private void validateUniquenessForCreate(String username, String email, String rut) {
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un usuario con el nombre de usuario " + username + ".");
        }
        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un usuario con el correo electrónico indicado.");
        }
        if (rut != null && userRepository.existsByRut(rut)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un usuario con el RUT " + rut + ".");
        }
    }

    private void validateUniquenessForUpdate(Long userId, String username, String email, String rut) {
        if (userRepository.existsByUsernameIgnoreCaseAndIdNot(username, userId)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un usuario con el nombre de usuario " + username + ".");
        }
        if (email != null && userRepository.existsByEmailIgnoreCaseAndIdNot(email, userId)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un usuario con el correo electrónico indicado.");
        }
        if (rut != null && userRepository.existsByRutAndIdNot(rut, userId)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un usuario con el RUT " + rut + ".");
        }
    }

    private List<RoleEntity> resolveRolesOrThrow(Set<Long> roleIds) {
        List<RoleEntity> roles = roleRepository.findAllById(roleIds);
        if (roles.size() != roleIds.size()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Uno o más roles indicados no existen.",
                    Map.of(),
                    List.of(new FieldErrorDetail("roleIds", "NOT_FOUND", "Existen identificadores de rol inválidos."))
            );
        }
        return roles;
    }

    private void validateStores(Set<Long> storeIds, Long defaultStoreId, Set<String> targetPermissions) {
        boolean hasAllAccess = targetPermissions.contains(Permissions.SECURITY_STORE_ALL_ACCESS);
        if (!hasAllAccess && storeIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Debe asignar al menos un local al usuario.",
                    Map.of(),
                    List.of(new FieldErrorDetail("storeIds", "REQUIRED",
                            "Debe asignar al menos un local cuando el usuario no tiene acceso global."))
            );
        }
        if (!storeIds.isEmpty()) {
            List<StoreInfo> activeStores = storeApi.findActiveByIds(storeIds);
            if (activeStores.size() != storeIds.size()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Uno o más locales indicados no existen o están inactivos.",
                        Map.of(),
                        List.of(new FieldErrorDetail("storeIds", "INVALID_STORE",
                                "Existen locales inválidos o inactivos en storeIds."))
                );
            }
        }
        if (defaultStoreId != null && !storeIds.contains(defaultStoreId)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El local por defecto debe pertenecer a los locales asignados.",
                    Map.of(),
                    List.of(new FieldErrorDetail("defaultStoreId", "INVALID_DEFAULT_STORE",
                            "defaultStoreId debe estar contenido en storeIds."))
            );
        }
    }

    private UserAccount findUserOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Usuario no encontrado."));
    }

    private UserResponse buildUserResponse(UserAccount user) {
        List<RoleSummary> roles = userQueryRepository.findRolesByUserId(user.getId());
        Set<Long> storeIds = userQueryRepository.findAssignedStoreIds(user.getId());
        Long defaultStoreId = authQueryRepository.findDefaultStoreId(user.getId()).orElse(null);
        List<StoreSummary> stores = resolveStoreSummaries(storeIds);
        return toUserResponse(user, roles, stores, defaultStoreId);
    }

    private UserResponse buildUserResponse(
            UserAccount user,
            List<RoleEntity> roles,
            Collection<Long> storeIds,
            Long defaultStoreId
    ) {
        List<RoleSummary> roleSummaries = roles.stream()
                .map(r -> new RoleSummary(r.getId(), r.getCode(), r.getName()))
                .toList();
        List<StoreSummary> stores = resolveStoreSummaries(storeIds);
        return toUserResponse(user, roleSummaries, stores, defaultStoreId);
    }

    private List<StoreSummary> resolveStoreSummaries(Collection<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of();
        }
        return storeApi.findActiveByIds(storeIds).stream()
                .map(s -> new StoreSummary(s.id(), s.code(), s.name(), s.active()))
                .toList();
    }

    private UserResponse toUserResponse(
            UserAccount user,
            List<RoleSummary> roles,
            List<StoreSummary> stores,
            Long defaultStoreId
    ) {
        boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(clock.now());
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                user.getRut(),
                user.isActive(),
                locked,
                user.getLockedUntil(),
                user.getPinHash() != null,
                roles,
                stores,
                defaultStoreId,
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getVersion()
        );
    }

    private static Map<String, Object> snapshotUser(
            UserAccount user,
            List<String> roleCodes,
            Collection<Long> storeIds,
            Long defaultStoreId
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("username", user.getUsername());
        snapshot.put("fullName", user.getFullName());
        snapshot.put("email", user.getEmail());
        snapshot.put("rut", user.getRut());
        snapshot.put("active", user.isActive());
        snapshot.put("roles", roleCodes);
        snapshot.put("storeIds", List.copyOf(storeIds));
        snapshot.put("defaultStoreId", defaultStoreId);
        return snapshot;
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeRut(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Rut.of(raw).normalized();
    }

    private String generateTemporaryPassword() {
        char[] chars = new char[TEMP_PASSWORD_LENGTH];
        chars[0] = TEMP_LETTERS.charAt(secureRandom.nextInt(TEMP_LETTERS.length()));
        chars[1] = TEMP_DIGITS.charAt(secureRandom.nextInt(TEMP_DIGITS.length()));
        for (int i = 2; i < TEMP_PASSWORD_LENGTH; i++) {
            chars[i] = TEMP_ALPHABET.charAt(secureRandom.nextInt(TEMP_ALPHABET.length()));
        }
        // Mezcla Fisher-Yates para no fijar la posición de la letra y el dígito garantizados
        for (int i = chars.length - 1; i > 0; i--) {
            int j = secureRandom.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }
}
