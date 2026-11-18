package cl.minimarket.pos.audit.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Representación de dominio de una fila de {@code audit_log}.
 *
 * @param id identificador autogenerado en BD ({@code null} antes de insertar)
 * @param occurredAt instante UTC del evento, truncado a microsegundos para coincidir con {@code timestamptz}
 * @param userId id del usuario autenticado, o {@code null} en eventos sin sesión
 * @param username copia del nombre de usuario al momento del evento
 * @param storeId id del local activo, o {@code null} en endpoints globales o jobs
 * @param terminalId id de la terminal activa, o {@code null} si no aplica
 * @param ip dirección IP del cliente (sin máscara CIDR), o {@code null} si no hay request HTTP
 * @param action código de acción auditada
 * @param entityType tipo de entidad afectada
 * @param entityId identificador de la entidad afectada
 * @param oldValue estado anterior en JSON, o {@code null}
 * @param newValue estado nuevo en JSON, o {@code null}
 * @param reason motivo o justificación, o {@code null}
 * @param authorizationId id de autorización de supervisor, o {@code null}
 * @param traceId identificador de traza del MDC, o {@code null}
 * @param prevHash hash SHA-256 de la fila inmediatamente anterior ({@code null} solo en la primera fila)
 * @param hash hash SHA-256 encadenado de esta fila
 */
public record AuditLogEntry(
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
        String prevHash,
        String hash
) {
}
