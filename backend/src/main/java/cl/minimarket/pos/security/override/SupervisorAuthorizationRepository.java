package cl.minimarket.pos.security.override;

import cl.minimarket.pos.security.AuthorizationRef;
import cl.minimarket.pos.security.OverrideAction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

/**
 * Repositorio JDBC para {@code supervisor_authorizations} (B.2, D.4).
 *
 * <p>Implementa inserción de autorizaciones {@code GRANTED}/{@code DENIED} y el consumo atómico
 * mediante {@code UPDATE ... WHERE consumed_at IS NULL RETURNING ...}.
 */
@Repository
public class SupervisorAuthorizationRepository {

    private final JdbcClient jdbc;

    /**
     * @param jdbc cliente JDBC de Spring
     */
    public SupervisorAuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserta un registro de autorización (otorgada o denegada) y retorna su id generado.
     */
    public Long insert(
            OverrideAction action,
            String status,
            Long requestedBy,
            Long authorizedBy,
            Long storeId,
            Long terminalId,
            OverrideMethod method,
            String contextJson,
            String contextHash,
            String tokenHash,
            Instant expiresAt,
            String reason,
            Instant createdAt
    ) {
        return jdbc.sql("""
                        INSERT INTO supervisor_authorizations (
                            action, status, requested_by, authorized_by, store_id, terminal_id,
                            method, context, context_hash, token_hash, expires_at, reason, created_at
                        )
                        VALUES (
                            :action, :status, :requestedBy, :authorizedBy, :storeId, :terminalId,
                            :method, CAST(:context AS jsonb), :contextHash, :tokenHash, :expiresAt, :reason, :createdAt
                        )
                        RETURNING id
                        """)
                .param("action", action.name())
                .param("status", status)
                .param("requestedBy", requestedBy)
                .param("authorizedBy", authorizedBy, Types.BIGINT)
                .param("storeId", storeId)
                .param("terminalId", terminalId, Types.BIGINT)
                .param("method", method.name())
                .param("context", contextJson)
                .param("contextHash", contextHash)
                .param("tokenHash", tokenHash, Types.CHAR)
                .param("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null, Types.TIMESTAMP)
                .param("reason", reason, Types.VARCHAR)
                .param("createdAt", Timestamp.from(createdAt))
                .query(Long.class)
                .single();
    }

    /**
     * Consume atómicamente una autorización {@code GRANTED} vigente y no consumida que coincida con
     * token, acción, contexto, solicitante, local y terminal.
     */
    public Optional<AuthorizationRef> consumeAtomically(
            String tokenHash,
            OverrideAction action,
            String contextHash,
            Long requesterId,
            Long storeId,
            Long terminalId,
            String entityType,
            String entityId,
            Instant now
    ) {
        return jdbc.sql("""
                        UPDATE supervisor_authorizations
                        SET consumed_at = :now,
                            entity_type = COALESCE(:entityType, entity_type),
                            entity_id   = COALESCE(:entityId, entity_id)
                        WHERE token_hash   = :tokenHash
                          AND status       = 'GRANTED'
                          AND consumed_at IS NULL
                          AND expires_at   > :now
                          AND action       = :action
                          AND context_hash = :contextHash
                          AND requested_by = :requesterId
                          AND store_id     = :storeId
                          AND (terminal_id IS NULL OR terminal_id = :terminalId)
                        RETURNING id, authorized_by, action
                        """)
                .param("now", Timestamp.from(now))
                .param("entityType", entityType, Types.VARCHAR)
                .param("entityId", entityId, Types.VARCHAR)
                .param("tokenHash", tokenHash)
                .param("action", action.name())
                .param("contextHash", contextHash)
                .param("requesterId", requesterId)
                .param("storeId", storeId)
                .param("terminalId", terminalId, Types.BIGINT)
                .query((rs, rowNum) -> new AuthorizationRef(
                        rs.getLong("id"),
                        rs.getLong("authorized_by"),
                        OverrideAction.valueOf(rs.getString("action"))
                ))
                .optional();
    }
}
