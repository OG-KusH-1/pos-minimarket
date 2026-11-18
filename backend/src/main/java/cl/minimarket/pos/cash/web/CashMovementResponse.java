package cl.minimarket.pos.cash.web;

import cl.minimarket.pos.cash.CashMovementType;
import cl.minimarket.pos.cash.application.CashSessionService.MovementDetail;
import cl.minimarket.pos.cash.domain.CashMovement;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Respuesta de un movimiento manual de efectivo ({@code API.md} F.3).
 */
public record CashMovementResponse(
        Long id,
        CashMovementType movementType,
        BigDecimal amount,
        String reason,
        String username,
        Instant occurredAt,
        Long authorizationId
) {
    /** Convierte el detalle de aplicación a DTO de respuesta. */
    public static CashMovementResponse from(MovementDetail detail) {
        CashMovement m = detail.movement();
        return new CashMovementResponse(
                m.getId(),
                m.getMovementType(),
                m.getAmount(),
                m.getReason(),
                detail.username(),
                m.getOccurredAt(),
                m.getAuthorizationId()
        );
    }
}
