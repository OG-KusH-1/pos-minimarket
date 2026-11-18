package cl.minimarket.pos.security.access;

import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.organization.TerminalApi;
import cl.minimarket.pos.security.auth.AuthQueryRepository;
import cl.minimarket.pos.shared.context.StoreAccessValidator;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;

import java.util.Objects;

/**
 * Implementación de {@link StoreAccessValidator} en el módulo {@code security} (PLAN A.4, L-03).
 *
 * <p>Valida en cada request con contexto de local que:
 * <ol>
 *   <li>El local exista y esté activo (vía {@link StoreApi}).</li>
 *   <li>El usuario tenga acceso al local (por {@code user_stores} o por el permiso
 *       {@link Permissions#SECURITY_STORE_ALL_ACCESS}). Si no cumple, responde 403
 *       {@code STORE_ACCESS_DENIED} sin distinguir entre inexistente, inactivo o no asignado.</li>
 *   <li>Si {@code terminalId} no es nulo, la terminal exista, esté activa y pertenezca al local
 *       indicado (vía {@link TerminalApi}, que lanza 422 {@code TERMINAL_INVALID}).</li>
 * </ol>
 */
public class StoreAccessValidatorImpl implements StoreAccessValidator {

    private final StoreApi storeApi;
    private final TerminalApi terminalApi;
    private final AuthQueryRepository authQueryRepository;

    /**
     * @param storeApi API pública de locales del módulo {@code organization}
     * @param terminalApi API pública de terminales del módulo {@code organization}
     * @param authQueryRepository consultas de permisos y asignación de locales del módulo {@code security}
     */
    public StoreAccessValidatorImpl(
            StoreApi storeApi,
            TerminalApi terminalApi,
            AuthQueryRepository authQueryRepository
    ) {
        this.storeApi = Objects.requireNonNull(storeApi, "storeApi");
        this.terminalApi = Objects.requireNonNull(terminalApi, "terminalApi");
        this.authQueryRepository = Objects.requireNonNull(authQueryRepository, "authQueryRepository");
    }

    @Override
    public void requireAccess(Long userId, Long storeId, Long terminalId) {
        if (userId == null || storeId == null) {
            throw new BusinessException(ErrorCode.STORE_ACCESS_DENIED, "Sin acceso al local.");
        }
        boolean storeActive = storeApi.find(storeId)
                .map(StoreInfo::active)
                .orElse(false);
        if (!storeActive) {
            throw new BusinessException(ErrorCode.STORE_ACCESS_DENIED, "Sin acceso al local.");
        }

        boolean allAccess = authQueryRepository.findPermissionCodesByUserId(userId)
                .contains(Permissions.SECURITY_STORE_ALL_ACCESS);
        if (!authQueryRepository.hasStoreAccess(userId, storeId, allAccess)) {
            throw new BusinessException(ErrorCode.STORE_ACCESS_DENIED, "Sin acceso al local.");
        }

        if (terminalId != null) {
            terminalApi.requireActive(terminalId, storeId);
        }
    }
}
