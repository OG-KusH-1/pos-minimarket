package cl.minimarket.pos.security.auth.refresh;

import java.time.Instant;
import java.util.UUID;

/**
 * Proyección interna de una fila de la tabla {@code refresh_tokens}.
 */
public record RefreshTokenRecord(
        Long id,
        Long userId,
        String tokenHash,
        UUID familyId,
        Long terminalId,
        Instant issuedAt,
        Instant expiresAt,
        Instant absoluteExpiresAt,
        Instant revokedAt,
        String revokedReason,
        Long replacedById
) {
}
