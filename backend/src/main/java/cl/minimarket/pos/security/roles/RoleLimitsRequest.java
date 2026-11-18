package cl.minimarket.pos.security.roles;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Solicitud para actualizar límites numéricos de un rol ({@code API.md} F.3).
 *
 * <p>Los códigos omitidos conservan su valor actual; {@code value = null} representa ilimitado (∞).
 */
public record RoleLimitsRequest(
        @NotNull(message = "La lista de límites es obligatoria.")
        List<@Valid @NotNull RoleLimitItemRequest> limits
) {
}
