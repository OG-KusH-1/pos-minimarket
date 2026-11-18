package cl.minimarket.pos.organization;

/**
 * Puerto de verificación previa antes de desactivar un local (PLAN A.4).
 *
 * <p>{@code organization} provee una implementación por defecto que permite todo; {@code cash} la
 * reemplaza en T29 para impedir desactivar un local con turnos de caja abiertos.
 */
public interface StoreUsageGuard {

    /**
     * Verifica si el local puede desactivarse.
     *
     * @param storeId id del local
     * @throws cl.minimarket.pos.shared.error.BusinessException 409 {@code ENTITY_IN_USE} si está en uso
     */
    void assertCanDeactivate(Long storeId);
}
