package cl.minimarket.pos.security.roles;

import cl.minimarket.pos.security.RoleLimitCode;

import java.math.BigDecimal;

/**
 * Respuesta de un límite configurado para un rol ({@code API.md} F.3).
 *
 * @param code código de límite
 * @param value valor máximo permitido, o {@code null} cuando es ilimitado (∞)
 */
public record RoleLimitResponse(
        RoleLimitCode code,
        BigDecimal value
) {
}
