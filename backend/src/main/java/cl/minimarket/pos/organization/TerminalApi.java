package cl.minimarket.pos.organization;

/**
 * API pública de terminales POS del módulo {@code organization} (PLAN A.4).
 */
public interface TerminalApi {

    /**
     * Verifica que la terminal exista, esté activa y pertenezca al local indicado.
     *
     * @param terminalId id de la terminal
     * @param storeId id del local esperado
     * @return información de la terminal activa
     * @throws cl.minimarket.pos.shared.error.BusinessException 422 {@code TERMINAL_INVALID} si no
     *         existe, está inactiva o pertenece a otro local
     */
    TerminalInfo requireActive(Long terminalId, Long storeId);
}
