package cl.minimarket.pos.security.pin;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.security.auth.AuthQueryRepository;
import cl.minimarket.pos.security.auth.CurrentUser;
import cl.minimarket.pos.security.auth.CurrentUserService;
import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.security.auth.dto.MeResponse;
import cl.minimarket.pos.security.auth.dto.StoreSummary;
import cl.minimarket.pos.security.auth.dto.TokenResponse;
import cl.minimarket.pos.security.auth.ratelimit.AuthRateLimiter;
import cl.minimarket.pos.security.auth.ratelimit.ClientIpResolver;
import cl.minimarket.pos.security.auth.ratelimit.LoginAttemptRepository;
import cl.minimarket.pos.security.auth.refresh.RefreshCookieProperties;
import cl.minimarket.pos.security.auth.refresh.RefreshTokenRecord;
import cl.minimarket.pos.security.auth.refresh.RefreshTokenRepository;
import cl.minimarket.pos.security.auth.refresh.RefreshTokenService;
import cl.minimarket.pos.security.pin.PinUserRepository.UserCredentialView;
import cl.minimarket.pos.security.pin.dto.ChangePinRequest;
import cl.minimarket.pos.security.pin.dto.PinLoginRequest;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Casos de uso de PIN personal: configuración ({@code PUT /auth/me/pin}) y cambio rápido de
 * operador en terminal POS ({@code POST /auth/pin-login}, scope {@link TokenScope#POS}).
 */
@Service
public class PinService {

    private static final String PIN_LOGIN_SCOPE = "pin-login";
    private static final String METHOD_PIN = "PIN";
    private static final int DEFAULT_PIN_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_PIN_LOCKOUT_MINUTES = 15;

    /**
     * Hash BCrypt costo 12 constante usado para igualar el tiempo de respuesta cuando el usuario no
     * existe, está inactivo o no tiene PIN configurado.
     */
    private static final String DUMMY_HASH =
            "$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW";

    private final PinUserRepository pinUserRepository;
    private final AuthQueryRepository queryRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshCookieProperties cookieProperties;
    private final CurrentUserService currentUserService;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter rateLimiter;
    private final LoginAttemptRepository attemptRepository;
    private final SettingsApi settingsApi;
    private final AuditService auditService;
    private final BusinessClock clock;
    private final TransactionTemplate requiresNewTx;

    /**
     * Constructor principal del servicio de PIN.
     */
    public PinService(
            PinUserRepository pinUserRepository,
            AuthQueryRepository queryRepository,
            RefreshTokenRepository refreshTokenRepository,
            RefreshCookieProperties cookieProperties,
            CurrentUserService currentUserService,
            JwtTokenService jwtTokenService,
            PasswordEncoder passwordEncoder,
            AuthRateLimiter rateLimiter,
            LoginAttemptRepository attemptRepository,
            SettingsApi settingsApi,
            AuditService auditService,
            BusinessClock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.pinUserRepository = pinUserRepository;
        this.queryRepository = queryRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.cookieProperties = cookieProperties;
        this.currentUserService = currentUserService;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.attemptRepository = attemptRepository;
        this.settingsApi = settingsApi;
        this.auditService = auditService;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Configura o actualiza el PIN del usuario autenticado previa verificación de su contraseña
     * actual y de las reglas de complejidad del PIN (F.2, F.3).
     *
     * <p>Reinicia el contador de intentos fallidos y levanta cualquier bloqueo de PIN vigente.
     */
    @Transactional
    public void changePin(ChangePinRequest request) {
        CurrentUser current = currentUserService.current();
        UserCredentialView user = pinUserRepository.findById(current.userId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHENTICATED, "Debe iniciar sesión para continuar."));

        if (!passwordEncoder.matches(request.currentPassword(), user.passwordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "La contraseña actual es incorrecta.");
        }

        validatePinStrength(request.newPin());

        String encodedPin = passwordEncoder.encode(request.newPin());
        pinUserRepository.updatePinHashAndResetFailures(user.id(), encodedPin, clock.now());
    }

    /**
     * Autentica a un operador mediante su PIN en una terminal POS ya ligada a la sesión vigente,
     * emitiendo un access token con alcance {@link TokenScope#POS} ({@code sales:*}, {@code cash:*},
     * {@code customers:*}).
     *
     * <p>Usa {@code noRollbackFor = BusinessException.class} para persistir intentos fallidos,
     * auto-desbloqueos y bloqueos de PIN en {@code users}, {@code login_attempts} y {@code audit_log}.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse pinLogin(PinLoginRequest request, HttpServletRequest httpRequest) {
        StoreContext storeContext = StoreContext.require();
        Long terminalId = storeContext.terminalId();
        if (terminalId == null) {
            throw new BusinessException(ErrorCode.TERMINAL_INVALID, "Se requiere una terminal activa.");
        }

        String username = request.username().trim();
        String ip = ClientIpResolver.resolveIp(httpRequest);
        Instant now = clock.now();

        checkRateLimit(username, ip, now);
        requireBoundTerminalSession(currentUserService.current().userId(), terminalId, httpRequest, now);

        UserCredentialView user = pinUserRepository.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.pin(), DUMMY_HASH);
            recordPinFailure(username, null, "BAD_CREDENTIALS", ip, now);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Usuario o PIN incorrectos.");
        }
        if (!user.active()) {
            passwordEncoder.matches(request.pin(), DUMMY_HASH);
            recordPinFailure(username, user.id(), "DISABLED", ip, now);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Usuario o PIN incorrectos.");
        }
        if (user.pinHash() == null) {
            passwordEncoder.matches(request.pin(), DUMMY_HASH);
            recordPinFailure(username, user.id(), "BAD_CREDENTIALS", ip, now);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Usuario o PIN incorrectos.");
        }

        boolean autoUnlocked = false;
        if (user.pinLocked()) {
            if (user.pinLockedUntil() != null && !user.pinLockedUntil().isAfter(now)) {
                autoUnlocked = true;
            } else {
                recordPinFailure(username, user.id(), "PIN_LOCKED", ip, now);
                throw new BusinessException(
                        ErrorCode.PIN_LOCKED,
                        "El PIN se encuentra bloqueado temporalmente por intentos fallidos.");
            }
        }

        if (!passwordEncoder.matches(request.pin(), user.pinHash())) {
            handleBadPin(user, autoUnlocked, username, ip, now);
        }

        if (autoUnlocked || user.pinFailedAttempts() > 0 || user.pinLocked() || user.pinLockedUntil() != null) {
            pinUserRepository.resetPinFailures(user.id(), now);
        }
        pinUserRepository.updateLastLoginAt(user.id(), now);
        attemptRepository.record(user.username(), user.id(), METHOD_PIN, ip, true, null, now);
        auditService.record(new AuditEvent(
                "AUTH_LOGIN_SUCCESS",
                "user",
                String.valueOf(user.id()),
                null,
                Map.of("method", METHOD_PIN, "terminalId", terminalId),
                null,
                null
        ));

        MeResponse me = buildPosMeResponse(user);
        JwtTokenService.IssuedToken issued = jwtTokenService.issueAccessToken(
                user.id(), user.username(), TokenScope.POS, me.permissions());

        return TokenResponse.bearer(
                issued.accessToken(),
                issued.expiresIn(),
                TokenScope.POS,
                user.mustChangePassword(),
                me
        );
    }

    private void checkRateLimit(String username, String ip, Instant now) {
        try {
            rateLimiter.checkAndConsume(PIN_LOGIN_SCOPE, ip, username);
        } catch (BusinessException ex) {
            if (ex.getCode() == ErrorCode.RATE_LIMITED) {
                requiresNewTx.executeWithoutResult(status ->
                        attemptRepository.record(username, null, METHOD_PIN, ip, false, "RATE_LIMITED", now));
            }
            throw ex;
        }
    }

    private void requireBoundTerminalSession(
            Long currentUserId,
            Long expectedTerminalId,
            HttpServletRequest httpRequest,
            Instant now
    ) {
        String rawToken = extractCookieValue(httpRequest)
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TOKEN_INVALID, "No existe una sesión de refresh vigente."));

        RefreshTokenRecord token = refreshTokenRepository
                .findByTokenHash(RefreshTokenService.sha256Hex(rawToken))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TOKEN_INVALID, "La sesión de refresh es inválida."));

        if (!token.userId().equals(currentUserId)
                || token.revokedAt() != null
                || !now.isBefore(token.expiresAt())
                || !now.isBefore(token.absoluteExpiresAt())) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "La sesión de refresh no está vigente.");
        }

        if (token.terminalId() == null || !token.terminalId().equals(expectedTerminalId)) {
            throw new BusinessException(
                    ErrorCode.TERMINAL_INVALID,
                    "La sesión vigente no está ligada a la terminal indicada.");
        }
    }

    private void handleBadPin(
            UserCredentialView user,
            boolean autoUnlocked,
            String username,
            String ip,
            Instant now
    ) {
        int baseAttempts = autoUnlocked ? 0 : user.pinFailedAttempts();
        int nextAttempts = baseAttempts + 1;
        int maxAttempts = resolveIntSetting("security.pin.max_attempts", DEFAULT_PIN_MAX_ATTEMPTS);
        int lockoutMinutes = resolveIntSetting("security.pin.lockout_minutes", DEFAULT_PIN_LOCKOUT_MINUTES);

        attemptRepository.record(username, user.id(), METHOD_PIN, ip, false, "BAD_CREDENTIALS", now);

        if (nextAttempts >= maxAttempts) {
            Instant lockedUntil = now.plus(Duration.ofMinutes(lockoutMinutes));
            pinUserRepository.updatePinFailureState(user.id(), nextAttempts, true, lockedUntil, now);
            auditService.record(new AuditEvent(
                    "AUTH_PIN_LOCKED",
                    "user",
                    String.valueOf(user.id()),
                    null,
                    Map.of(
                            "method", METHOD_PIN,
                            "failedAttempts", nextAttempts,
                            "lockedUntil", lockedUntil.toString()
                    ),
                    null,
                    null
            ));
            throw new BusinessException(
                    ErrorCode.PIN_LOCKED,
                    "El PIN ha sido bloqueado temporalmente por intentos fallidos.");
        }

        pinUserRepository.updatePinFailureState(user.id(), nextAttempts, false, null, now);
        auditService.record(new AuditEvent(
                "AUTH_LOGIN_FAILED",
                "user",
                String.valueOf(user.id()),
                null,
                Map.of("method", METHOD_PIN, "reason", "BAD_CREDENTIALS", "username", username),
                null,
                null
        ));
        throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Usuario o PIN incorrectos.");
    }

    private void recordPinFailure(String username, Long userId, String reason, String ip, Instant now) {
        attemptRepository.record(username, userId, METHOD_PIN, ip, false, reason, now);
        auditService.record(new AuditEvent(
                "AUTH_LOGIN_FAILED",
                "user",
                userId != null ? String.valueOf(userId) : "0",
                null,
                Map.of("method", METHOD_PIN, "reason", reason, "username", username),
                null,
                null
        ));
    }

    private MeResponse buildPosMeResponse(UserCredentialView user) {
        List<String> roles = queryRepository.findRoleCodesByUserId(user.id());
        List<String> allPermissions = queryRepository.findPermissionCodesByUserId(user.id());
        List<String> posPermissions = allPermissions.stream()
                .filter(PinService::isPosScopePermission)
                .toList();
        boolean allAccess = allPermissions.contains(Permissions.SECURITY_STORE_ALL_ACCESS);
        List<StoreSummary> stores = queryRepository.findAccessibleStores(user.id(), allAccess);
        Long defaultStoreId = queryRepository.findDefaultStoreId(user.id()).orElse(null);
        Map<String, BigDecimal> limits = queryRepository.findEffectiveLimitsByUserId(user.id());

        return new MeResponse(
                user.id(),
                user.username(),
                user.fullName(),
                roles,
                posPermissions,
                stores,
                defaultStoreId,
                limits,
                TokenScope.POS,
                user.pinHash() != null
        );
    }

    private Optional<String> extractCookieValue(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return Optional.empty();
        }
        String cookieName = cookieProperties.name();
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private int resolveIntSetting(String key, int fallback) {
        try {
            Integer configured = settingsApi.get(key, null, Integer.class);
            return configured != null && configured > 0 ? configured : fallback;
        } catch (BusinessException ex) {
            return fallback;
        }
    }

    /**
     * Valida que el PIN no tenga todos sus dígitos iguales ni forme una secuencia consecutiva
     * ascendente o descendente (p. ej. {@code 1111}, {@code 1234}, {@code 9876}).
     */
    static void validatePinStrength(String pin) {
        if (pin.chars().distinct().count() <= 1) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El PIN no puede tener todos los dígitos iguales.");
        }
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < pin.length(); i++) {
            int diff = pin.charAt(i) - pin.charAt(i - 1);
            if (diff != 1) {
                ascending = false;
            }
            if (diff != -1) {
                descending = false;
            }
        }
        if (ascending || descending) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El PIN no puede formar una secuencia consecutiva de dígitos.");
        }
    }

    private static boolean isPosScopePermission(String code) {
        return code.startsWith("sales:") || code.startsWith("cash:") || code.startsWith("customers:");
    }
}
