package cl.minimarket.pos.security.auth;

import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.security.auth.dto.ChangePasswordRequest;
import cl.minimarket.pos.security.auth.dto.LoginRequest;
import cl.minimarket.pos.security.auth.dto.MeResponse;
import cl.minimarket.pos.security.auth.dto.StoreSummary;
import cl.minimarket.pos.security.auth.dto.TokenResponse;
import cl.minimarket.pos.security.config.SecurityProperties;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Casos de uso de autenticación núcleo: inicio de sesión con contraseña, consulta del usuario
 * actual ({@code /auth/me}) y cambio de contraseña ({@code /auth/me/password}).
 */
@Service
public class AuthService {

    private static final LoginAttemptHook NOOP_HOOK = new LoginAttemptHook() {
    };
    /**
     * Hash BCrypt costo 12 constante usado para igualar el tiempo de respuesta cuando el usuario no
     * existe o está inactivo, evitando enumeración de cuentas por temporización.
     */
    private static final String DUMMY_HASH =
            "$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW";

    private final AuthUserRepository userRepository;
    private final AuthQueryRepository queryRepository;
    private final SettingsApi settingsApi;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final CurrentUserService currentUserService;
    private final SecurityProperties securityProperties;
    private final BusinessClock clock;
    private final LoginAttemptHook loginAttemptHook;

    /**
     * @param userRepository repositorio JPA de usuarios
     * @param queryRepository repositorio de consultas de roles, permisos, locales y límites
     * @param settingsApi API pública de parámetros de configuración del módulo {@code organization}
     * @param passwordEncoder codificador BCrypt (costo 12)
     * @param jwtTokenService emisor de JWT HS256
     * @param currentUserService resolvedor del usuario autenticado actual
     * @param securityProperties propiedades de seguridad
     * @param clock reloj de negocio
     * @param loginAttemptHookProvider punto de extensión opcional para T21 (rate limit, intentos, refresh)
     */
    public AuthService(
            AuthUserRepository userRepository,
            AuthQueryRepository queryRepository,
            SettingsApi settingsApi,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            CurrentUserService currentUserService,
            SecurityProperties securityProperties,
            BusinessClock clock,
            ObjectProvider<LoginAttemptHook> loginAttemptHookProvider
    ) {
        this.userRepository = userRepository;
        this.queryRepository = queryRepository;
        this.settingsApi = settingsApi;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.currentUserService = currentUserService;
        this.securityProperties = securityProperties;
        this.clock = clock;
        this.loginAttemptHook = loginAttemptHookProvider.getIfAvailable(() -> NOOP_HOOK);
    }

    /**
     * Autentica al usuario con nombre de usuario y contraseña (BCrypt costo 12) y emite un
     * {@link TokenResponse} con alcance {@link TokenScope#FULL}.
     *
     * <p>Usa {@code noRollbackFor = BusinessException.class} para que el hook de T21 pueda persistir
     * intentos fallidos y bloqueos aunque el método lance {@code INVALID_CREDENTIALS} o {@code ACCOUNT_LOCKED}.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String username = request.username().trim();
        loginAttemptHook.beforePasswordLogin(username, httpRequest);

        AuthUserEntity user = userRepository.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            loginAttemptHook.onPasswordLoginFailure(username, null, "BAD_CREDENTIALS", httpRequest);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Usuario o contraseña incorrectos.");
        }
        if (!user.isActive()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            loginAttemptHook.onPasswordLoginFailure(username, user, "DISABLED", httpRequest);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Usuario o contraseña incorrectos.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptHook.onPasswordLoginFailure(username, user, "BAD_CREDENTIALS", httpRequest);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Usuario o contraseña incorrectos.");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(clock.now())) {
            loginAttemptHook.onPasswordLoginFailure(username, user, "LOCKED", httpRequest);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                    "La cuenta se encuentra bloqueada temporalmente por intentos fallidos.");
        }

        user.setLastLoginAt(clock.now());
        userRepository.save(user);
        loginAttemptHook.onPasswordLoginSuccess(user, httpRequest, httpResponse);

        MeResponse me = buildMeResponse(user, TokenScope.FULL);
        JwtTokenService.IssuedToken issued = jwtTokenService.issueAccessToken(
                user.getId(), user.getUsername(), TokenScope.FULL, me.permissions());

        return TokenResponse.bearer(
                issued.accessToken(),
                issued.expiresIn(),
                TokenScope.FULL,
                user.isMustChangePassword(),
                me
        );
    }

    /**
     * Retorna el perfil completo del usuario autenticado en el contexto actual.
     */
    @Transactional(readOnly = true)
    public MeResponse me() {
        CurrentUser current = currentUserService.current();
        AuthUserEntity user = userRepository.findById(current.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Debe iniciar sesión para continuar."));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "La cuenta de usuario está inactiva.");
        }
        return buildMeResponse(user, current.scope());
    }

    /**
     * Cambia la contraseña del usuario autenticado, valida la política vigente y limpia el flag
     * {@code must_change_password}.
     */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        CurrentUser current = currentUserService.current();
        AuthUserEntity user = userRepository.findById(current.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Debe iniciar sesión para continuar."));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "La contraseña actual es incorrecta.");
        }

        validatePasswordPolicy(request.newPassword(), user);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(clock.now());
        userRepository.save(user);

        loginAttemptHook.onPasswordChanged(user.getId());
    }

    private void validatePasswordPolicy(String newPassword, AuthUserEntity user) {
        int minLength = resolvePasswordMinLength();

        if (newPassword.length() < minLength || newPassword.length() > 128) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY,
                    "La contraseña debe tener entre " + minLength + " y 128 caracteres.");
        }
        boolean hasLetter = newPassword.chars().anyMatch(Character::isLetter);
        boolean hasDigit = newPassword.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY,
                    "La contraseña debe incluir al menos una letra y un número.");
        }
        if (newPassword.equalsIgnoreCase(user.getUsername())) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY,
                    "La nueva contraseña no puede ser igual al nombre de usuario.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY,
                    "La nueva contraseña debe ser distinta a la contraseña actual.");
        }
    }

    private int resolvePasswordMinLength() {
        try {
            Integer configured = settingsApi.get("security.password.min_length", null, Integer.class);
            return configured != null ? configured : securityProperties.password().minLength();
        } catch (BusinessException ex) {
            return securityProperties.password().minLength();
        }
    }

    private MeResponse buildMeResponse(AuthUserEntity user, TokenScope scope) {
        List<String> roles = queryRepository.findRoleCodesByUserId(user.getId());
        List<String> allPermissions = queryRepository.findPermissionCodesByUserId(user.getId());
        List<String> effectivePermissions = scope == TokenScope.POS
                ? allPermissions.stream().filter(AuthService::isPosScopePermission).toList()
                : allPermissions;
        boolean allAccess = allPermissions.contains(Permissions.SECURITY_STORE_ALL_ACCESS);
        List<StoreSummary> stores = queryRepository.findAccessibleStores(user.getId(), allAccess);
        Long defaultStoreId = queryRepository.findDefaultStoreId(user.getId()).orElse(null);
        Map<String, BigDecimal> limits = queryRepository.findEffectiveLimitsByUserId(user.getId());

        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                roles,
                effectivePermissions,
                stores,
                defaultStoreId,
                limits,
                scope,
                user.getPinHash() != null
        );
    }

    private static boolean isPosScopePermission(String code) {
        return code.startsWith("sales:") || code.startsWith("cash:") || code.startsWith("customers:");
    }
}
