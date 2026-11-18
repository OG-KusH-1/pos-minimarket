package cl.minimarket.pos.audit.application;

import java.time.Instant;

/**
 * Criterios de filtro para consultar la bitácora de auditoría ({@code GET /api/v1/audit-log}).
 *
 * @param from instante inicial inclusivo ({@code occurred_at >= from}), o {@code null}
 * @param to instante final inclusivo ({@code occurred_at <= to}), o {@code null}
 * @param action código exacto de acción auditada, o {@code null}
 * @param entityType tipo exacto de entidad, o {@code null}
 * @param entityId identificador exacto de entidad, o {@code null}
 * @param userId id del usuario autor del evento, o {@code null}
 * @param storeId id del local del evento, o {@code null}
 */
public record AuditLogFilter(
        Instant from,
        Instant to,
        String action,
        String entityType,
        String entityId,
        Long userId,
        Long storeId
) {
}
