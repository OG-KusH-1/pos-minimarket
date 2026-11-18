package cl.minimarket.pos.audit.web;

import cl.minimarket.pos.audit.domain.AuditLogEntry;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Representación pública de un registro de la bitácora de auditoría (F.3 {@code AuditLogResponse}).
 *
 * @param id identificador único del registro
 * @param occurredAt instante UTC en que ocurrió el evento
 * @param userId id del usuario autor, o {@code null} si no aplica
 * @param username copia del nombre de usuario al momento del evento
 * @param storeId id del local activo, o {@code null} en eventos globales
 * @param terminalId id de la terminal activa, o {@code null} si no aplica
 * @param ip dirección IP del cliente, o {@code null} si no aplica
 * @param action código de acción auditada
 * @param entityType tipo de entidad afectada
 * @param entityId identificador de la entidad afectada
 * @param oldValue estado previo en JSON, o {@code null}
 * @param newValue estado posterior en JSON, o {@code null}
 * @param reason motivo o justificación, o {@code null}
 * @param authorizationId id de autorización de supervisor, o {@code null}
 * @param traceId identificador de traza de la solicitud, o {@code null}
 * @param hash hash SHA-256 encadenado del registro
 */
public record AuditLogResponse(
        Long id,
        Instant occurredAt,
        Long userId,
        String username,
        Long storeId,
        Long terminalId,
        String ip,
        String action,
        String entityType,
        String entityId,
        JsonNode oldValue,
        JsonNode newValue,
        String reason,
        Long authorizationId,
        String traceId,
        String hash
) {

    /**
     * Convierte una entrada de dominio en su DTO de respuesta HTTP.
     *
     * @param entry entrada de {@code audit_log}
     * @return DTO de respuesta
     */
    public static AuditLogResponse from(AuditLogEntry entry) {
        return new AuditLogResponse(
                entry.id(),
                entry.occurredAt(),
                entry.userId(),
                entry.username(),
                entry.storeId(),
                entry.terminalId(),
                entry.ip(),
                entry.action(),
                entry.entityType(),
                entry.entityId(),
                entry.oldValue(),
                entry.newValue(),
                entry.reason(),
                entry.authorizationId(),
                entry.traceId(),
                entry.hash()
        );
    }
}
