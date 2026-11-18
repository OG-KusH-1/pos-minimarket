package cl.minimarket.pos.security;

/**
 * API pública del módulo {@code security} para consumir autorizaciones de supervisor (overrides)
 * y calcular hashes canónicos de contexto ({@code PLAN.md} A.4, {@code ROLES_PERMISOS.md} D.4).
 */
public interface SupervisorOverrideApi {

    /**
     * Consume atómicamente un token de override de un solo uso emitido por {@code POST /auth/overrides}.
     *
     * <p>Valida además el local y la terminal activos desde {@code StoreContext.require()}.
     *
     * @param token token opaco enviado en el header {@code X-Override-Token}
     * @param action acción esperada para el override
     * @param contextHash hash SHA-256 hexadecimal del contexto canónico de la operación
     * @param requesterId identificador del usuario solicitante que ejecuta la operación
     * @return referencia de la autorización consumida (incluye {@code authorizationId} para auditoría)
     * @throws cl.minimarket.pos.shared.error.BusinessException 422 {@code OVERRIDE_INVALID} si el token
     *         es nulo, inválido, expiró, ya fue consumido o no coincide con acción, contexto,
     *         solicitante, local o terminal
     */
    AuthorizationRef consume(String token, OverrideAction action, String contextHash, Long requesterId);

    /**
     * Variante de {@link #consume(String, OverrideAction, String, Long)} que registra además el
     * tipo e identificador de la entidad afectada en {@code supervisor_authorizations}.
     *
     * @param token token opaco enviado en el header {@code X-Override-Token}
     * @param action acción esperada para el override
     * @param contextHash hash SHA-256 hexadecimal del contexto canónico de la operación
     * @param requesterId identificador del usuario solicitante que ejecuta la operación
     * @param entityType tipo de entidad afectada (p. ej. {@code "sale"}, {@code "cash_movement"}), opcional
     * @param entityId identificador de la entidad afectada, opcional
     * @return referencia de la autorización consumida
     */
    AuthorizationRef consume(
            String token,
            OverrideAction action,
            String contextHash,
            Long requesterId,
            String entityType,
            String entityId
    );

    /**
     * Calcula el hash SHA-256 hexadecimal (64 caracteres) del JSON canónico de {@code context},
     * ordenando recursivamente las claves de objetos y normalizando números decimales.
     *
     * @param context objeto, mapa o {@code JsonNode} representativo del contexto del override
     * @return hash SHA-256 en minúsculas
     */
    String canonicalContextHash(Object context);
}
