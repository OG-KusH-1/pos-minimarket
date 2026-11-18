package cl.minimarket.pos.security;

import java.util.Map;

/**
 * API pública del módulo {@code security} para consultar los límites efectivos por rol de un usuario
 * ({@code PLAN.md} A.4, {@code ROLES_PERMISOS.md} D.3).
 *
 * <p>Implementa semántica <em>fail-closed</em>: si el usuario no tiene roles o falta una fila en
 * {@code role_limits}, devuelve {@link RoleLimit#zero()}. Si el usuario tiene múltiples roles,
 * prevalece el límite más permisivo ({@link RoleLimit.Unlimited} sobre cualquier {@link RoleLimit.Limited},
 * y entre límites acotados el mayor valor).
 */
public interface RoleLimitApi {

    /**
     * Obtiene el límite efectivo de un usuario para un código específico.
     *
     * @param userId identificador del usuario
     * @param code código del límite a consultar
     * @return {@link RoleLimit.Unlimited} o {@link RoleLimit.Limited} (nunca nulo)
     */
    RoleLimit limitFor(Long userId, RoleLimitCode code);

    /**
     * Obtiene el mapa completo de límites efectivos del usuario para todos los códigos de {@link RoleLimitCode}.
     *
     * @param userId identificador del usuario
     * @return mapa inmutable con todos los códigos de {@link RoleLimitCode}
     */
    Map<RoleLimitCode, RoleLimit> limitsFor(Long userId);
}
