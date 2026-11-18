package cl.minimarket.pos.shared.context;

/**
 * Puerto que valida que un usuario puede operar sobre un local (y, si se indica, una terminal de
 * ese local). Lo <b>define shared</b> y lo <b>implementa security</b> (T21) apoyándose en
 * {@code StoreApi} y {@code TerminalApi}; así shared no depende de ningún otro módulo (A.4).
 *
 * <p>{@link StoreContextFilter} lo invoca en cada request autenticada que no es global
 * (ADR-12: el local se revalida siempre del lado del servidor; no viaja dentro del JWT).
 *
 * <p>Contrato de la implementación:
 * <ul>
 *   <li>Debe ser rápida y sin efectos secundarios: se ejecuta en cada request.</li>
 *   <li>Si el usuario no tiene acceso al local, o el local no existe o está inactivo, lanza
 *       {@code BusinessException(ErrorCode.STORE_ACCESS_DENIED, ...)} (403). No debe distinguir
 *       entre "no existe" y "no tiene acceso" para no revelar la existencia de locales.</li>
 *   <li>Si {@code terminalId} no es nulo y no corresponde a una terminal activa del local, lanza
 *       {@code BusinessException(ErrorCode.TERMINAL_INVALID, ...)} (422).</li>
 *   <li>Un usuario con acceso a todos los locales (rol all-access) pasa la validación de local.</li>
 * </ul>
 */
public interface StoreAccessValidator {

    /**
     * Verifica el acceso del usuario al local y a la terminal.
     *
     * @param userId id del usuario autenticado; nunca nulo
     * @param storeId id del local activo declarado por el cliente; nunca nulo
     * @param terminalId id de la terminal declarada; {@code null} si el cliente no envió terminal
     * @throws cl.minimarket.pos.shared.error.BusinessException {@code STORE_ACCESS_DENIED} si no
     *         hay acceso al local, {@code TERMINAL_INVALID} si la terminal no pertenece al local
     */
    void requireAccess(Long userId, Long storeId, Long terminalId);
}
