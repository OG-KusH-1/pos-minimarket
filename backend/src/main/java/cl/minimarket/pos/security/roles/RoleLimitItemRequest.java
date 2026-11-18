package cl.minimarket.pos.security.roles;

import cl.minimarket.pos.security.RoleLimitCode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Entrada individual para actualizar un límite de rol ({@code API.md} F.3).
 *
 * @param code código de límite
 * @param value valor máximo permitido ({@code >= 0}), o {@code null} para ilimitado (∞)
 */
public record RoleLimitItemRequest(
        @NotNull(message = "El código de límite es obligatorio.")
        RoleLimitCode code,

        @DecimalMin(value = "0", message = "El valor del límite debe ser mayor o igual a 0.")
        BigDecimal value
) {
}
