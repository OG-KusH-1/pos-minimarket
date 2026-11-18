package cl.minimarket.pos.organization;

/**
 * Puerto de verificación previa antes de desactivar una terminal POS (PLAN A.4).
 *
 * <p>{@code organization} provee una implementación por defecto que permite todo; {@code cash} la
 * reemplaza en T29 para impedir desactivar una terminal con un turno de caja abierto.
 */
public interface TerminalUsageGuard {

    /**
     * Verifica si la terminal puede desactivarse.
     *
     * @param terminalId id de la terminal
     * @throws cl.minimarket.pos.shared.error.BusinessException 409 {@code ENTITY_IN_USE} si está en uso
     */
    void assertCanDeactivate(Long terminalId);
}
