package cl.minimarket.pos.security.auth.refresh;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.organization.TerminalApi;
import cl.minimarket.pos.security.auth.AuthQueryRepository;
import cl.minimarket.pos.security.auth.AuthUserEntity;
import cl.minimarket.pos.security.auth.AuthUserRepository;
import cl.minimarket.pos.security.auth.CurrentUser;
import cl.minimarket.pos.security.auth.CurrentUserService;
import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.security.auth.dto.MeResponse;
import cl.minimarket.pos.security.auth.dto.StoreSummary;
import cl.minimarket.pos.security.auth.dto.TokenResponse;
import cl.minimarket.pos.security.auth.ratelimit.ClientIpResolver;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gestión del ciclo de vida de refresh tokens con rotación, familias (RN-32), cookie
 * {@code HttpOnly} y asociación a terminal POS ({@code POST /auth/session/terminal}).
 */
@Service
@EnableConfigurationProperties(RefreshCookieProperties.class)
public class RefreshTokenService {

    private static final int DEFAULT_REFRESH_TTL_HOURS = 12;
    private static final int DEFAULT_ABSOLUTE_DAYS = 7;
    private static final int TOKEN_BYTE_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthUserRepository userRepository;
    private final AuthQueryRepository queryRepository;
    private final JwtTokenService jwtTokenService;
    private final CurrentUserService currentUserService;
    private final TerminalApi terminalApi;
    private final SettingsApi settingsApi;
    private final AuditService auditService;
    private final BusinessClock clock;
    private final RefreshCookieProperties cookieProperties;

    /**
     * Constructor principal con todas las dependencias del ciclo de sesión.
     */
    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            AuthUserRepository userRepository,
            AuthQueryRepository queryRepository,
            JwtTokenService jwtTokenService,
            CurrentUserService currentUserService,
            TerminalApi terminalApi,
            SettingsApi settingsApi,
            AuditService auditService,
            BusinessClock clock,
            RefreshCookieProperties cookieProperties
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.queryRepository = queryRepository;
        this.jwtTokenService = jwtTokenService;
        this.currentUserService = currentUserService;
        this.terminalApi = terminalApi;
        this.settingsApi = settingsApi;
        this.auditService = auditService;
        this.clock = clock;
        this.cookieProperties = cookieProperties;
    }

    /**
     * Emite un refresh token inicial (nueva familia, sin terminal) al iniciar sesión y escribe la
     * cookie {@code pos_refresh} en la respuesta HTTP.
     */
    @Transactional
    public void issueOnLogin(Long userId, HttpServletRequest request, HttpServletResponse response) {
        Instant now = clock.now();
        int ttlHours = resolveIntSetting("security.refresh.ttl_hours", DEFAULT_REFRESH_TTL_HOURS);
        int absoluteDays = resolveIntSetting("security.refresh.absolute_days", DEFAULT_ABSOLUTE_DAYS);

        Instant absoluteExpiresAt = now.plus(Duration.ofDays(absoluteDays));
        Instant slidingExpiresAt = now.plus(Duration.ofHours(ttlHours));
        Instant expiresAt = slidingExpiresAt.isBefore(absoluteExpiresAt) ? slidingExpiresAt : absoluteExpiresAt;

        String rawToken = generateRawToken();
        String tokenHash = sha256Hex(rawToken);
        UUID familyId = UUID.randomUUID();

        refreshTokenRepository.insert(
                userId,
                tokenHash,
                familyId,
                null,
                now,
                expiresAt,
                absoluteExpiresAt,
                ClientIpResolver.resolveIp(request),
                ClientIpResolver.resolveUserAgent(request)
        );
        writeRefreshCookie(response, rawToken, Duration.between(now, expiresAt));
    }

    /**
     * Rota el refresh token recibido en la cookie {@code pos_refresh}, conservando {@code family_id},
     * {@code terminal_id} y {@code absolute_expires_at}.
     *
     * <p>Si se detecta reutilización de un token ya rotado, revoca toda la familia, audita
     * {@code AUTH_TOKEN_REUSE_DETECTED}, limpia la cookie y responde 401 {@code TOKEN_INVALID}.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = extractCookieValue(request).orElse(null);
        if (rawToken == null || rawToken.isBlank()) {
            clearRefreshCookie(response);
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "Falta el token de refresco.");
        }

        RefreshTokenRecord current = refreshTokenRepository.findByTokenHash(sha256Hex(rawToken))
                .orElse(null);
        if (current == null) {
            clearRefreshCookie(response);
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "El token de refresco es inválido.");
        }

        Instant now = clock.now();
        if (current.revokedAt() != null) {
            if ("ROTATED".equals(current.revokedReason())) {
                refreshTokenRepository.revokeFamily(current.familyId(), now, "REUSE_DETECTED");
                auditService.record(new AuditEvent(
                        "AUTH_TOKEN_REUSE_DETECTED",
                        "user",
                        String.valueOf(current.userId()),
                        null,
                        Map.of("familyId", current.familyId().toString()),
                        null,
                        null
                ));
            }
            clearRefreshCookie(response);
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "El token de refresco fue revocado.");
        }

        if (!now.isBefore(current.expiresAt()) || !now.isBefore(current.absoluteExpiresAt())) {
            clearRefreshCookie(response);
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "El token de refresco ha expirado.");
        }

        AuthUserEntity user = userRepository.findById(current.userId()).orElse(null);
        if (user == null || !user.isActive()) {
            refreshTokenRepository.revokeFamily(current.familyId(), now, "USER_DISABLED");
            clearRefreshCookie(response);
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "La cuenta de usuario no está activa.");
        }

        int ttlHours = resolveIntSetting("security.refresh.ttl_hours", DEFAULT_REFRESH_TTL_HOURS);
        Instant slidingExpiresAt = now.plus(Duration.ofHours(ttlHours));
        Instant nextExpiresAt = slidingExpiresAt.isBefore(current.absoluteExpiresAt())
                ? slidingExpiresAt
                : current.absoluteExpiresAt();

        String nextRawToken = generateRawToken();
        Long nextId = refreshTokenRepository.insert(
                current.userId(),
                sha256Hex(nextRawToken),
                current.familyId(),
                current.terminalId(),
                now,
                nextExpiresAt,
                current.absoluteExpiresAt(),
                ClientIpResolver.resolveIp(request),
                ClientIpResolver.resolveUserAgent(request)
        );
        refreshTokenRepository.markRotated(current.id(), now, nextId);
        writeRefreshCookie(response, nextRawToken, Duration.between(now, nextExpiresAt));

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
     * Cierra la sesión actual revocando la familia del refresh token recibido y borrando la cookie.
     */
    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        CurrentUser current = currentUserService.current();
        extractCookieValue(request)
                .filter(v -> !v.isBlank())
                .map(RefreshTokenService::sha256Hex)
                .flatMap(refreshTokenRepository::findByTokenHash)
                .filter(token -> token.userId().equals(current.userId()))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.familyId(), clock.now(), "LOGOUT"));
        clearRefreshCookie(response);
    }

    /**
     * Liga el refresh token vigente de la cookie {@code pos_refresh} a la terminal indicada,
     * validando que la terminal esté activa y pertenezca al local de {@code X-Store-Id}.
     */
    @Transactional
    public void bindSessionTerminal(SessionTerminalRequest request, HttpServletRequest httpRequest) {
        CurrentUser current = currentUserService.current();
        Long storeId = StoreContext.require().storeId();
        terminalApi.requireActive(request.terminalId(), storeId);

        String rawToken = extractCookieValue(httpRequest)
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TOKEN_INVALID, "No existe una sesión de refresh activa."));

        RefreshTokenRecord token = refreshTokenRepository.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TOKEN_INVALID, "El token de refresco es inválido."));

        Instant now = clock.now();
        if (!token.userId().equals(current.userId())
                || token.revokedAt() != null
                || !now.isBefore(token.expiresAt())
                || !now.isBefore(token.absoluteExpiresAt())) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "El token de refresco no está vigente.");
        }

        refreshTokenRepository.bindTerminal(token.id(), request.terminalId());
    }

    /**
     * Revoca todos los refresh tokens activos de un usuario (por ejemplo al cambiar contraseña).
     */
    @Transactional
    public void revokeAllForUser(Long userId, String reason) {
        refreshTokenRepository.revokeAllActiveByUserId(userId, clock.now(), reason);
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

    private void writeRefreshCookie(HttpServletResponse response, String value, Duration maxAge) {
        if (response == null) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(cookieProperties.name(), value)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite("Strict")
                .path(cookieProperties.path())
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        if (response == null) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(cookieProperties.name(), "")
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite("Strict")
                .path(cookieProperties.path())
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private int resolveIntSetting(String key, int fallback) {
        try {
            Integer value = settingsApi.get(key, null, Integer.class);
            return value != null && value > 0 ? value : fallback;
        } catch (BusinessException ex) {
            return fallback;
        }
    }

    private MeResponse buildMeResponse(AuthUserEntity user, TokenScope scope) {
        List<String> roles = queryRepository.findRoleCodesByUserId(user.getId());
        List<String> permissions = queryRepository.findPermissionCodesByUserId(user.getId());
        boolean allAccess = permissions.contains(Permissions.SECURITY_STORE_ALL_ACCESS);
        List<StoreSummary> stores = queryRepository.findAccessibleStores(user.getId(), allAccess);
        Long defaultStoreId = queryRepository.findDefaultStoreId(user.getId()).orElse(null);
        Map<String, BigDecimal> limits = queryRepository.findEffectiveLimitsByUserId(user.getId());

        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                roles,
                permissions,
                stores,
                defaultStoreId,
                limits,
                scope,
                user.getPinHash() != null
        );
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Calcula el hash SHA-256 hexadecimal en minúsculas (64 caracteres).
     */
    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Algoritmo SHA-256 no disponible", ex);
        }
    }
}
