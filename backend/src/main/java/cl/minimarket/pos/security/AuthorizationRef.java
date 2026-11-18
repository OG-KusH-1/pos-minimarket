package cl.minimarket.pos.security;

/**
 * Referencia inmutable a una autorización de supervisor ya consumida ({@code PLAN.md} A.4).
 *
 * @param authorizationId identificador de la fila en {@code supervisor_authorizations}
 * @param authorizedBy identificador del supervisor que otorgó la autorización
 * @param action acción autorizada
 */
public record AuthorizationRef(
        Long authorizationId,
        Long authorizedBy,
        OverrideAction action
) {
}
