package cl.minimarket.pos.security.auth.ratelimit;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.security.auth.AuthUserEntity;
import cl.minimarket.pos.security.auth.AuthUserRepository;
import cl.minimarket.pos.security.auth.LoginAttemptHook;
import cl.minimarket.pos.security.auth.refresh.RefreshTokenService;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Implementación de {@link LoginAttemptHook} para T21:
 * <ul>
 *   <li>Rate limit Bucket4j por IP y por usuario objetivo antes de validar credenciales.</li>
 *   <li>Registro de todos los intentos (exitosos y fallidos) en {@code login_attempts}.</li>
 *   <li>Incremento de {@code failed_attempts} y bloqueo temporal ({@code locked_until}) tras N fallos.</li>
 *   <li>Emisión de cookie de refresh token al iniciar sesión y revocación al cambiar contraseña.</li>
 * </ul>
 */
@Service
public class LoginAttemptService implements LoginAttemptHook {

    private static final String LOGIN_SCOPE = "login";
    private static final String METHOD_PASSWORD = "PASSWORD";

    private final AuthRateLimiter rateLimiter;
    private final LoginAttemptRepository attemptRepository;
    private final AuthUserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final SettingsApi settingsApi;
    private final AuditService auditService;
    private final BusinessClock clock;
    private final LockoutProperties lockoutProperties;
    private final TransactionTemplate requiresNewTx;

    /**
     * Constructor con dependencias de rate limit, persistencia de intentos, bloqueo, refresh y auditoría.
     */
    public LoginAttemptService(
            AuthRateLimiter rateLimiter,
            LoginAttemptRepository attemptRepository,
            AuthUserRepository userRepository,
            RefreshTokenService refreshTokenService,
            SettingsApi settingsApi,
            AuditService auditService,
            BusinessClock clock,
            LockoutProperties lockoutProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.rateLimiter = rateLimiter;
        this.attemptRepository = attemptRepository;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.settingsApi = settingsApi;
        this.auditService = auditService;
        this.clock = clock;
        this.lockoutProperties = lockoutProperties;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void beforePasswordLogin(String username, HttpServletRequest request) {
        String ip = ClientIpResolver.resolveIp(request);
        try {
            rateLimiter.checkAndConsume(LOGIN_SCOPE, ip, username);
        } catch (BusinessException ex) {
            if (ex.getCode() == ErrorCode.RATE_LIMITED) {
                Instant now = clock.now();
                requiresNewTx.executeWithoutResult(status ->
                        attemptRepository.record(username, null, METHOD_PASSWORD, ip, false, "RATE_LIMITED", now));
            }
            throw ex;
        }
    }

    @Override
    public void onPasswordLoginFailure(String username, AuthUserEntity user, String reason, HttpServletRequest request) {
        String ip = ClientIpResolver.resolveIp(request);
        Instant now = clock.now();
        Long userId = user != null ? user.getId() : null;

        attemptRepository.record(username, userId, METHOD_PASSWORD, ip, false, reason, now);

        if ("BAD_CREDENTIALS".equals(reason) && user != null) {
            int maxAttempts = resolveIntSetting("security.lockout.max_attempts", lockoutProperties.maxFailedAttempts());
            int lockoutMinutes = resolveIntSetting(
                    "security.lockout.minutes",
                    Math.max(1, (int) lockoutProperties.duration().toMinutes())
            );

            int nextAttempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(nextAttempts);

            if (nextAttempts >= maxAttempts) {
                Instant lockedUntil = now.plus(Duration.ofMinutes(lockoutMinutes));
                user.setLockedUntil(lockedUntil);
                userRepository.save(user);
                auditService.record(new AuditEvent(
                        "AUTH_LOCKED",
                        "user",
                        String.valueOf(user.getId()),
                        null,
                        Map.of(
                                "method", METHOD_PASSWORD,
                                "failedAttempts", nextAttempts,
                                "lockedUntil", lockedUntil.toString()
                        ),
                        null,
                        null
                ));
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                        "La cuenta ha sido bloqueada temporalmente por intentos fallidos.");
            }

            userRepository.save(user);
        }

        auditService.record(new AuditEvent(
                "AUTH_LOGIN_FAILED",
                "user",
                userId != null ? String.valueOf(userId) : "0",
                null,
                Map.of("method", METHOD_PASSWORD, "reason", reason, "username", username),
                null,
                null
        ));
    }

    @Override
    public void onPasswordLoginSuccess(AuthUserEntity user, HttpServletRequest request, HttpServletResponse response) {
        String ip = ClientIpResolver.resolveIp(request);
        Instant now = clock.now();

        if (user.getFailedAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        attemptRepository.record(user.getUsername(), user.getId(), METHOD_PASSWORD, ip, true, null, now);
        refreshTokenService.issueOnLogin(user.getId(), request, response);

        auditService.record(new AuditEvent(
                "AUTH_LOGIN_SUCCESS",
                "user",
                String.valueOf(user.getId()),
                null,
                Map.of("method", METHOD_PASSWORD),
                null,
                null
        ));
    }

    @Override
    public void onPasswordChanged(Long userId) {
        refreshTokenService.revokeAllForUser(userId, "LOGOUT");
    }

    private int resolveIntSetting(String key, int fallback) {
        try {
            Integer configured = settingsApi.get(key, null, Integer.class);
            return configured != null && configured > 0 ? configured : fallback;
        } catch (BusinessException ex) {
            return fallback;
        }
    }
}
