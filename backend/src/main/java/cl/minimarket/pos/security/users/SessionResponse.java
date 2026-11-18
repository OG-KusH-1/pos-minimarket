package cl.minimarket.pos.security.users;

import java.time.Instant;

/**
 * Respuesta de una sesión activa (refresh token vigente) de un usuario ({@code API.md} F.3).
 */
public record SessionResponse(
        Long id,
        Instant issuedAt,
        Instant expiresAt,
        String ip,
        String userAgent,
        Long terminalId
) {
}
