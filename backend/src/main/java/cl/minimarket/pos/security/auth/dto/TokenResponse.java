package cl.minimarket.pos.security.auth.dto;

import cl.minimarket.pos.security.auth.TokenScope;

/**
 * Respuesta de autenticación con access token JWT y datos del usuario ({@code POST /api/v1/auth/login}).
 *
 * @param accessToken token JWT de acceso firmado con HS256
 * @param tokenType tipo de token ({@code "Bearer"})
 * @param expiresIn tiempo de expiración del token en segundos
 * @param scope alcance del token ({@code FULL} o {@code POS})
 * @param mustChangePassword indica si el usuario debe cambiar su contraseña antes de operar
 * @param user perfil completo del usuario autenticado
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        TokenScope scope,
        boolean mustChangePassword,
        MeResponse user
) {

    /** Constructor de conveniencia con {@code tokenType = "Bearer"}. */
    public static TokenResponse bearer(String accessToken, long expiresIn, TokenScope scope,
                                       boolean mustChangePassword, MeResponse user) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, scope, mustChangePassword, user);
    }
}
