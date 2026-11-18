package cl.minimarket.pos.security.override;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.security.AuthorizationRef;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.RoleLimit;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.security.auth.AuthQueryRepository;
import cl.minimarket.pos.security.auth.CurrentUser;
import cl.minimarket.pos.security.auth.CurrentUserService;
import cl.minimarket.pos.security.auth.ratelimit.AuthRateLimiter;
import cl.minimarket.pos.security.auth.ratelimit.ClientIpResolver;
import cl.minimarket.pos.security.auth.ratelimit.LockoutProperties;
import cl.minimarket.pos.security.auth.ratelimit.LoginAttemptRepository;
import cl.minimarket.pos.security.override.dto.OverrideRequest;
import cl.minimarket.pos.security.override.dto.OverrideResponse;
import cl.minimarket.pos.security.pin.PinUserRepository;
import cl.minimarket.pos.security.pin.PinUserRepository.UserCredentialView;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Servicio de autorizaciones de supervisor (overrides) e implementación de {@link SupervisorOverrideApi}
 * ({@code ROLES_PERMISOS.md} D.4, {@code MODELO_DATOS.md} B.2, {@code PLAN.md} A.4).
 */
@Service
public class SupervisorOverrideService implements SupervisorOverrideApi {

    private static final String OVERRIDE_SCOPE = "override";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);
    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final int DEFAULT_PIN_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_PIN_LOCKOUT_MINUTES = 15;

    /**
     * Hash BCrypt costo 12 constante usado para igualar temporización cuando el supervisor no existe,
     * está inactivo o no tiene PIN configurado.
     */
    private static final String DUMMY_HASH =
            "$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW";

    private final SecureRandom secureRandom = new SecureRandom();
    private final SupervisorAuthorizationRepository authorizationRepository;
    private final PinUserRepository userRepository;
    private final AuthQueryRepository queryRepository;
    private final RoleLimitApi roleLimitApi;
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter rateLimiter;
    private final LoginAttemptRepository attemptRepository;
    private final SettingsApi settingsApi;
    private final LockoutProperties lockoutProperties;
    private final AuditService auditService;
    private final BusinessClock clock;
    private final TransactionTemplate requiresNewTx;

    /**
     * Constructor principal del servicio de overrides de supervisor.
     */
    public SupervisorOverrideService(
            SupervisorAuthorizationRepository authorizationRepository,
            PinUserRepository userRepository,
            AuthQueryRepository queryRepository,
            RoleLimitApi roleLimitApi,
            CurrentUserService currentUserService,
            PasswordEncoder passwordEncoder,
            AuthRateLimiter rateLimiter,
            LoginAttemptRepository attemptRepository,
            SettingsApi settingsApi,
            LockoutProperties lockoutProperties,
            AuditService auditService,
            BusinessClock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.authorizationRepository = authorizationRepository;
        this.userRepository = userRepository;
        this.queryRepository = queryRepository;
        this.roleLimitApi = roleLimitApi;
        this.currentUserService = currentUserService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.attemptRepository = attemptRepository;
        this.settingsApi = settingsApi;
        this.lockoutProperties = lockoutProperties;
        this.auditService = auditService;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Valida la autorización de un supervisor y emite un token de override de un solo uso con
     * vigencia de 5 minutos, registrando tanto aprobaciones ({@code GRANTED}) como rechazos
     * ({@code DENIED}) en {@code supervisor_authorizations} y {@code audit_log}.
     *
     * <p>Usa {@code noRollbackFor = BusinessException.class} para persistir registros {@code DENIED},
     * incrementos de {@code failed_attempts}/{@code pin_failed_attempts} y bloqueos de cuenta/PIN.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public OverrideResponse authorize(OverrideRequest request, HttpServletRequest httpRequest) {
        if (!request.context().isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El contexto del override debe ser un objeto JSON.");
        }

        CurrentUser requester = currentUserService.current();
        StoreContext storeContext = StoreContext.require();
        String supervisorUsername = request.supervisorUsername().trim();
        String ip = ClientIpResolver.resolveIp(httpRequest);
        Instant now = clock.now();

        String canonicalContext = CanonicalJsonHasher.toCanonicalJson(request.context());
        String contextHash = CanonicalJsonHasher.sha256Hex(canonicalContext);

        checkRateLimit(supervisorUsername, request.method(), ip, now);

        UserCredentialView supervisor = userRepository.findByUsernameIgnoreCase(supervisorUsername).orElse(null);
        if (supervisor == null || !supervisor.active()) {
            passwordEncoder.matches(request.secret(), DUMMY_HASH);
            String reasonCode = supervisor == null ? "BAD_CREDENTIALS" : "DISABLED";
            attemptRepository.record(
                    supervisorUsername,
                    supervisor != null ? supervisor.id() : null,
                    request.method().name(),
                    ip,
                    false,
                    reasonCode,
                    now
            );
            recordDenied(request, requester.userId(), null, storeContext, canonicalContext, contextHash,
                    "Supervisor inexistente o inactivo", now);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Credenciales de supervisor inválidas.");
        }

        if (supervisor.id().equals(requester.userId())) {
            // El constraint ck_supervisor_authorizations_sod exige authorized_by IS NULL OR authorized_by <> requested_by
            recordDenied(request, requester.userId(), null, storeContext, canonicalContext, contextHash,
                    "Segregación de funciones: el solicitante no puede auto-autorizarse", now);
            throw new BusinessException(
                    ErrorCode.SEGREGATION_OF_DUTIES,
                    "El solicitante no puede autorizar su propio override.");
        }

        boolean autoUnlockedPin = verifyNotLockedAndCredentials(
                supervisor, request, requester.userId(), storeContext, canonicalContext, contextHash, ip, now);

        if (autoUnlockedPin && request.method() == OverrideMethod.PIN) {
            userRepository.resetPinFailures(supervisor.id(), now);
        }

        List<String> permissions = queryRepository.findPermissionCodesByUserId(supervisor.id());
        boolean allAccess = permissions.contains(Permissions.SECURITY_STORE_ALL_ACCESS);
        if (!queryRepository.hasStoreAccess(supervisor.id(), storeContext.storeId(), allAccess)) {
            recordDenied(request, requester.userId(), supervisor.id(), storeContext, canonicalContext, contextHash,
                    "Supervisor sin acceso al local", now);
            throw new BusinessException(ErrorCode.OVERRIDE_INVALID, "El supervisor no tiene acceso a este local.");
        }

        String requiredPermission = requiredPermissionFor(request.action());
        if (!permissions.contains(requiredPermission)) {
            recordDenied(request, requester.userId(), supervisor.id(), storeContext, canonicalContext, contextHash,
                    "Supervisor sin permiso " + requiredPermission, now);
            throw new BusinessException(
                    ErrorCode.OVERRIDE_INVALID,
                    "El supervisor no tiene permiso para autorizar esta acción.");
        }

        if (!withinSupervisorLimits(supervisor.id(), request.action(), request.context())) {
            recordDenied(request, requester.userId(), supervisor.id(), storeContext, canonicalContext, contextHash,
                    "Límite del supervisor insuficiente para el contexto", now);
            throw new BusinessException(
                    ErrorCode.OVERRIDE_LIMIT_EXCEEDED,
                    "El límite del supervisor no cubre el monto o porcentaje solicitado.");
        }

        String rawToken = generateRawToken();
        String tokenHash = CanonicalJsonHasher.sha256Hex(rawToken);
        Instant expiresAt = now.plus(TOKEN_TTL);

        Long authorizationId = authorizationRepository.insert(
                request.action(),
                "GRANTED",
                requester.userId(),
                supervisor.id(),
                storeContext.storeId(),
                storeContext.terminalId(),
                request.method(),
                canonicalContext,
                contextHash,
                tokenHash,
                expiresAt,
                request.reason(),
                now
        );

        auditService.record(new AuditEvent(
                "OVERRIDE_GRANTED",
                "supervisor_authorization",
                String.valueOf(authorizationId),
                null,
                Map.of(
                        "action", request.action().name(),
                        "authorizedBy", supervisor.id(),
                        "method", request.method().name(),
                        "context", request.context()
                ),
                request.reason(),
                authorizationId
        ));

        return new OverrideResponse(rawToken, authorizationId, expiresAt);
    }

    @Override
    @Transactional
    public AuthorizationRef consume(String token, OverrideAction action, String contextHash, Long requesterId) {
        return consume(token, action, contextHash, requesterId, null, null);
    }

    @Override
    @Transactional
    public AuthorizationRef consume(
            String token,
            OverrideAction action,
            String contextHash,
            Long requesterId,
            String entityType,
            String entityId
    ) {
        if (token == null || token.isBlank() || action == null || contextHash == null || requesterId == null) {
            throw new BusinessException(ErrorCode.OVERRIDE_INVALID, "El token de autorización es inválido.");
        }
        StoreContext storeContext = StoreContext.require();
        String tokenHash = CanonicalJsonHasher.sha256Hex(token.trim());
        Instant now = clock.now();

        return authorizationRepository.consumeAtomically(
                        tokenHash,
                        action,
                        contextHash,
                        requesterId,
                        storeContext.storeId(),
                        storeContext.terminalId(),
                        entityType,
                        entityId,
                        now
                )
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.OVERRIDE_INVALID,
                        "El token de autorización es inválido, expiró o ya fue utilizado."));
    }

    @Override
    public String canonicalContextHash(Object context) {
        return CanonicalJsonHasher.hash(context);
    }

    private void checkRateLimit(String supervisorUsername, OverrideMethod method, String ip, Instant now) {
        try {
            rateLimiter.checkAndConsume(OVERRIDE_SCOPE, ip, supervisorUsername);
        } catch (BusinessException ex) {
            if (ex.getCode() == ErrorCode.RATE_LIMITED) {
                requiresNewTx.executeWithoutResult(status -> attemptRepository.record(
                        supervisorUsername, null, method.name(), ip, false, "RATE_LIMITED", now));
            }
            throw ex;
        }
    }

    private boolean verifyNotLockedAndCredentials(
            UserCredentialView supervisor,
            OverrideRequest request,
            Long requesterId,
            StoreContext storeContext,
            String canonicalContext,
            String contextHash,
            String ip,
            Instant now
    ) {
        if (request.method() == OverrideMethod.PIN) {
            return verifyPinMethod(
                    supervisor, request, requesterId, storeContext, canonicalContext, contextHash, ip, now);
        }
        verifyPasswordMethod(
                supervisor, request, requesterId, storeContext, canonicalContext, contextHash, ip, now);
        return false;
    }

    private boolean verifyPinMethod(
            UserCredentialView supervisor,
            OverrideRequest request,
            Long requesterId,
            StoreContext storeContext,
            String canonicalContext,
            String contextHash,
            String ip,
            Instant now
    ) {
        if (supervisor.pinHash() == null) {
            passwordEncoder.matches(request.secret(), DUMMY_HASH);
            attemptRepository.record(supervisor.username(), supervisor.id(), "PIN", ip, false, "BAD_CREDENTIALS", now);
            recordDenied(request, requesterId, supervisor.id(), storeContext, canonicalContext, contextHash,
                    "Supervisor sin PIN configurado", now);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Credenciales de supervisor inválidas.");
        }

        boolean autoUnlocked = false;
        if (supervisor.pinLocked()) {
            if (supervisor.pinLockedUntil() != null && !supervisor.pinLockedUntil().isAfter(now)) {
                autoUnlocked = true;
            } else {
                attemptRepository.record(supervisor.username(), supervisor.id(), "PIN", ip, false, "PIN_LOCKED", now);
                recordDenied(request, requesterId, supervisor.id(), storeContext, canonicalContext, contextHash,
                        "PIN del supervisor bloqueado", now);
                throw new BusinessException(
                        ErrorCode.PIN_LOCKED,
                        "El PIN del supervisor se encuentra bloqueado temporalmente.");
            }
        }

        if (!passwordEncoder.matches(request.secret(), supervisor.pinHash())) {
            int baseAttempts = autoUnlocked ? 0 : supervisor.pinFailedAttempts();
            int nextAttempts = baseAttempts + 1;
            int maxAttempts = resolveIntSetting("security.pin.max_attempts", DEFAULT_PIN_MAX_ATTEMPTS);
            int lockoutMinutes = resolveIntSetting("security.pin.lockout_minutes", DEFAULT_PIN_LOCKOUT_MINUTES);

            attemptRepository.record(supervisor.username(), supervisor.id(), "PIN", ip, false, "BAD_CREDENTIALS", now);
            recordDenied(request, requesterId, supervisor.id(), storeContext, canonicalContext, contextHash,
                    "PIN de supervisor incorrecto", now);

            if (nextAttempts >= maxAttempts) {
                Instant lockedUntil = now.plus(Duration.ofMinutes(lockoutMinutes));
                userRepository.updatePinFailureState(supervisor.id(), nextAttempts, true, lockedUntil, now);
                auditService.record(new AuditEvent(
                        "AUTH_PIN_LOCKED",
                        "user",
                        String.valueOf(supervisor.id()),
                        null,
                        Map.of(
                                "method", "PIN",
                                "failedAttempts", nextAttempts,
                                "lockedUntil", lockedUntil.toString()
                        ),
                        null,
                        null
                ));
                throw new BusinessException(
                        ErrorCode.PIN_LOCKED,
                        "El PIN del supervisor ha sido bloqueado temporalmente por intentos fallidos.");
            }

            userRepository.updatePinFailureState(supervisor.id(), nextAttempts, false, null, now);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Credenciales de supervisor inválidas.");
        }

        if (!autoUnlocked && (supervisor.pinFailedAttempts() > 0 || supervisor.pinLockedUntil() != null)) {
            userRepository.resetPinFailures(supervisor.id(), now);
        }
        attemptRepository.record(supervisor.username(), supervisor.id(), "PIN", ip, true, null, now);
        return autoUnlocked;
    }

    private void verifyPasswordMethod(
            UserCredentialView supervisor,
            OverrideRequest request,
            Long requesterId,
            StoreContext storeContext,
            String canonicalContext,
            String contextHash,
            String ip,
            Instant now
    ) {
        if (!passwordEncoder.matches(request.secret(), supervisor.passwordHash())) {
            int maxAttempts = resolveIntSetting("security.lockout.max_attempts", lockoutProperties.maxFailedAttempts());
            int lockoutMinutes = resolveIntSetting(
                    "security.lockout.minutes",
                    Math.max(1, (int) lockoutProperties.duration().toMinutes())
            );
            int nextAttempts = supervisor.failedAttempts() + 1;

            attemptRepository.record(
                    supervisor.username(), supervisor.id(), "PASSWORD", ip, false, "BAD_CREDENTIALS", now);
            recordDenied(request, requesterId, supervisor.id(), storeContext, canonicalContext, contextHash,
                    "Contraseña de supervisor incorrecta", now);

            if (nextAttempts >= maxAttempts) {
                Instant lockedUntil = now.plus(Duration.ofMinutes(lockoutMinutes));
                userRepository.updatePasswordFailureState(supervisor.id(), nextAttempts, lockedUntil, now);
                auditService.record(new AuditEvent(
                        "AUTH_LOCKED",
                        "user",
                        String.valueOf(supervisor.id()),
                        null,
                        Map.of(
                                "method", "PASSWORD",
                                "failedAttempts", nextAttempts,
                                "lockedUntil", lockedUntil.toString()
                        ),
                        null,
                        null
                ));
                throw new BusinessException(
                        ErrorCode.ACCOUNT_LOCKED,
                        "La cuenta del supervisor ha sido bloqueada temporalmente por intentos fallidos.");
            }

            userRepository.updatePasswordFailureState(supervisor.id(), nextAttempts, supervisor.lockedUntil(), now);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Credenciales de supervisor inválidas.");
        }

        if (supervisor.lockedUntil() != null && supervisor.lockedUntil().isAfter(now)) {
            attemptRepository.record(supervisor.username(), supervisor.id(), "PASSWORD", ip, false, "LOCKED", now);
            recordDenied(request, requesterId, supervisor.id(), storeContext, canonicalContext, contextHash,
                    "Cuenta del supervisor bloqueada", now);
            throw new BusinessException(
                    ErrorCode.ACCOUNT_LOCKED,
                    "La cuenta del supervisor se encuentra bloqueada temporalmente por intentos fallidos.");
        }

        if (supervisor.failedAttempts() > 0 || supervisor.lockedUntil() != null) {
            userRepository.updatePasswordFailureState(supervisor.id(), 0, null, now);
        }
        attemptRepository.record(supervisor.username(), supervisor.id(), "PASSWORD", ip, true, null, now);
    }

    private void recordDenied(
            OverrideRequest request,
            Long requesterId,
            Long authorizedBy,
            StoreContext storeContext,
            String canonicalContext,
            String contextHash,
            String denialReason,
            Instant now
    ) {
        String reasonToStore = request.reason() != null && !request.reason().isBlank()
                ? request.reason()
                : denialReason;
        Long authId = authorizationRepository.insert(
                request.action(),
                "DENIED",
                requesterId,
                authorizedBy,
                storeContext.storeId(),
                storeContext.terminalId(),
                request.method(),
                canonicalContext,
                contextHash,
                null,
                null,
                reasonToStore,
                now
        );
        auditService.record(new AuditEvent(
                "OVERRIDE_DENIED",
                "supervisor_authorization",
                String.valueOf(authId),
                null,
                Map.of(
                        "action", request.action().name(),
                        "method", request.method().name(),
                        "denialReason", denialReason,
                        "context", request.context()
                ),
                reasonToStore,
                authId
        ));
    }

    private boolean withinSupervisorLimits(Long supervisorId, OverrideAction action, JsonNode context) {
        return switch (action) {
            case DISCOUNT_ITEM_OVER_LIMIT -> {
                BigDecimal percent = extractDecimal(context, "percent");
                yield percent == null || roleLimitApi.limitFor(supervisorId, RoleLimitCode.DISCOUNT_ITEM_MAX_PCT).allows(percent);
            }
            case DISCOUNT_TOTAL_OVER_LIMIT -> {
                BigDecimal percent = extractDecimal(context, "percent");
                BigDecimal amount = extractDecimal(context, "amount");
                boolean pctAllowed = percent == null
                        || roleLimitApi.limitFor(supervisorId, RoleLimitCode.DISCOUNT_TOTAL_MAX_PCT).allows(percent);
                boolean amountAllowed = amount == null
                        || roleLimitApi.limitFor(supervisorId, RoleLimitCode.DISCOUNT_TOTAL_MAX_AMOUNT).allows(amount);
                yield pctAllowed && amountAllowed;
            }
            case SALE_RETURN -> checkAmountLimit(supervisorId, RoleLimitCode.RETURN_MAX_AMOUNT, context);
            case CASH_OUT_OVER_LIMIT -> checkAmountLimit(supervisorId, RoleLimitCode.CASH_OUT_MAX_AMOUNT, context);
            case SHRINKAGE_APPROVAL -> checkAmountLimit(supervisorId, RoleLimitCode.SHRINKAGE_APPROVE_MAX_AMOUNT, context);
            case STOCK_ADJUSTMENT_APPROVAL ->
                    checkAmountLimit(supervisorId, RoleLimitCode.ADJUSTMENT_APPROVE_MAX_AMOUNT, context);
            case SALE_VOID, CREDIT_OVER_LIMIT, SUSPENDED_SALE_DELETE, RECEIPT_REPRINT, CASH_DIFFERENCE_APPROVAL ->
                    true;
        };
    }

    private boolean checkAmountLimit(Long supervisorId, RoleLimitCode code, JsonNode context) {
        BigDecimal amount = extractDecimal(context, "amount");
        if (amount == null) {
            return true;
        }
        RoleLimit limit = roleLimitApi.limitFor(supervisorId, code);
        return limit.allows(amount);
    }

    private static BigDecimal extractDecimal(JsonNode context, String field) {
        JsonNode node = context.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isString() && !node.asString().isBlank()) {
            try {
                return new BigDecimal(node.asString().trim());
            } catch (NumberFormatException ex) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "El campo '" + field + "' del contexto debe ser numérico.");
            }
        }
        throw new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "El campo '" + field + "' del contexto debe ser numérico.");
    }

    private static String requiredPermissionFor(OverrideAction action) {
        return switch (action) {
            case DISCOUNT_ITEM_OVER_LIMIT, DISCOUNT_TOTAL_OVER_LIMIT -> Permissions.SALES_DISCOUNT_OVERRIDE;
            case SALE_VOID -> Permissions.SALES_SALE_VOID;
            case SALE_RETURN -> Permissions.SALES_RETURN_CREATE;
            case CASH_OUT_OVER_LIMIT -> Permissions.CASH_MOVEMENT_OVERRIDE;
            case CREDIT_OVER_LIMIT -> Permissions.CUSTOMERS_CREDIT_OVERRIDE;
            case SUSPENDED_SALE_DELETE -> Permissions.SALES_SUSPENDED_DELETE_OTHERS;
            case RECEIPT_REPRINT -> Permissions.SALES_RECEIPT_REPRINT;
            case SHRINKAGE_APPROVAL -> Permissions.SHRINKAGE_RECORD_APPROVE;
            case STOCK_ADJUSTMENT_APPROVAL -> Permissions.INVENTORY_ADJUSTMENT_APPROVE;
            case CASH_DIFFERENCE_APPROVAL -> Permissions.CASH_DIFFERENCE_APPROVE;
        };
    }

    private int resolveIntSetting(String key, int fallback) {
        try {
            Integer configured = settingsApi.get(key, null, Integer.class);
            return configured != null && configured > 0 ? configured : fallback;
        } catch (BusinessException ex) {
            return fallback;
        }
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
