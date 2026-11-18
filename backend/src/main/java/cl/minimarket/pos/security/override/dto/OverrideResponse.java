package cl.minimarket.pos.security.override.dto;

import java.time.Instant;

/**
 * Respuesta tras otorgar un token de override de supervisor de un solo uso
 * ({@code POST /api/v1/auth/overrides}, API.md F.3).
 *
 * @param overrideToken token opaco para enviar en el header {@code X-Override-Token}
 * @param authorizationId identificador de la fila en {@code supervisor_authorizations}
 * @param expiresAt instante de vencimiento del token (5 minutos desde su emisión)
 */
public record OverrideResponse(
        String overrideToken,
        Long authorizationId,
        Instant expiresAt
) {
}
