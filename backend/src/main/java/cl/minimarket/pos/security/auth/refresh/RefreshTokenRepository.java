package cl.minimarket.pos.security.auth.refresh;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio JDBC para la gestión de {@code refresh_tokens} (rotación, familias y terminal).
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcClient jdbc;

    /**
     * @param jdbc cliente JDBC de Spring
     */
    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserta un nuevo refresh token y devuelve su identificador generado.
     */
    public Long insert(
            Long userId,
            String tokenHash,
            UUID familyId,
            Long terminalId,
            Instant issuedAt,
            Instant expiresAt,
            Instant absoluteExpiresAt,
            String ip,
            String userAgent
    ) {
        return jdbc.sql("""
                        INSERT INTO refresh_tokens (
                            user_id, token_hash, family_id, terminal_id,
                            issued_at, expires_at, absolute_expires_at, ip, user_agent
                        )
                        VALUES (
                            :userId, :tokenHash, :familyId, :terminalId,
                            :issuedAt, :expiresAt, :absoluteExpiresAt, CAST(:ip AS inet), :userAgent
                        )
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("tokenHash", tokenHash)
                .param("familyId", familyId)
                .param("terminalId", terminalId)
                .param("issuedAt", Timestamp.from(issuedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("absoluteExpiresAt", Timestamp.from(absoluteExpiresAt))
                .param("ip", ip)
                .param("userAgent", userAgent)
                .query(Long.class)
                .single();
    }

    /**
     * Busca un refresh token por su hash SHA-256 hexadecimal.
     */
    public Optional<RefreshTokenRecord> findByTokenHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, user_id, token_hash, family_id, terminal_id,
                               issued_at, expires_at, absolute_expires_at,
                               revoked_at, revoked_reason, replaced_by_id
                        FROM refresh_tokens
                        WHERE token_hash = :tokenHash
                        """)
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> {
                    Timestamp revokedTs = rs.getTimestamp("revoked_at");
                    long terminalVal = rs.getLong("terminal_id");
                    Long terminalId = rs.wasNull() ? null : terminalVal;
                    long replacedVal = rs.getLong("replaced_by_id");
                    Long replacedById = rs.wasNull() ? null : replacedVal;
                    return new RefreshTokenRecord(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getString("token_hash"),
                            rs.getObject("family_id", UUID.class),
                            terminalId,
                            rs.getTimestamp("issued_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("absolute_expires_at").toInstant(),
                            revokedTs != null ? revokedTs.toInstant() : null,
                            rs.getString("revoked_reason"),
                            replacedById
                    );
                })
                .optional();
    }

    /**
     * Marca un refresh token como rotado y apunta al token sucesor.
     */
    public void markRotated(Long id, Instant revokedAt, Long replacedById) {
        jdbc.sql("""
                        UPDATE refresh_tokens
                        SET revoked_at = :revokedAt,
                            revoked_reason = 'ROTATED',
                            replaced_by_id = :replacedById
                        WHERE id = :id
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("replacedById", replacedById)
                .param("id", id)
                .update();
    }

    /**
     * Revoca todos los tokens activos de una familia con el motivo indicado.
     */
    public int revokeFamily(UUID familyId, Instant revokedAt, String reason) {
        return jdbc.sql("""
                        UPDATE refresh_tokens
                        SET revoked_at = :revokedAt,
                            revoked_reason = :reason
                        WHERE family_id = :familyId
                          AND revoked_at IS NULL
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("reason", reason)
                .param("familyId", familyId)
                .update();
    }

    /**
     * Revoca todos los refresh tokens activos de un usuario.
     */
    public int revokeAllActiveByUserId(Long userId, Instant revokedAt, String reason) {
        return jdbc.sql("""
                        UPDATE refresh_tokens
                        SET revoked_at = :revokedAt,
                            revoked_reason = :reason
                        WHERE user_id = :userId
                          AND revoked_at IS NULL
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("reason", reason)
                .param("userId", userId)
                .update();
    }

    /**
     * Asocia una terminal al refresh token activo indicado.
     */
    public void bindTerminal(Long id, Long terminalId) {
        jdbc.sql("""
                        UPDATE refresh_tokens
                        SET terminal_id = :terminalId
                        WHERE id = :id
                        """)
                .param("terminalId", terminalId)
                .param("id", id)
                .update();
    }
}
