package cl.minimarket.pos.cash;

/**
 * API pública del módulo {@code cash} para consulta de turnos abiertos desde otros módulos ({@code PLAN.md} A.4).
 */
public interface CashSessionApi {

    /**
     * Exige que exista un turno de caja en estado {@link CashSessionStatus#OPEN} para la terminal
     * indicada (RN-09).
     *
     * @param terminalId identificador de la terminal POS
     * @return referencia al turno abierto
     * @throws cl.minimarket.pos.shared.error.BusinessException 422 {@code CASH_SESSION_NOT_OPEN} si la
     *         terminal no tiene un turno abierto
     */
    CashSessionRef requireOpen(Long terminalId);

    /**
     * Indica si un turno de caja específico sigue en estado {@link CashSessionStatus#OPEN} (RN-12).
     *
     * @param cashSessionId identificador del turno
     * @return {@code true} si existe y está abierto
     */
    boolean isOpen(Long cashSessionId);
}
