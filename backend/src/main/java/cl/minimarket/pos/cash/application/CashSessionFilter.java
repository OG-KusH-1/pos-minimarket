package cl.minimarket.pos.cash.application;

import cl.minimarket.pos.cash.CashSessionStatus;

import java.time.LocalDate;

/**
 * Filtros soportados para el historial de turnos de caja ({@code GET /cash-sessions}).
 *
 * @param from fecha de negocio mínima (inclusive)
 * @param to fecha de negocio máxima (inclusive)
 * @param cashierId filtrar por cajero (ignorado si el usuario tiene alcance P)
 * @param terminalId filtrar por terminal POS
 * @param status filtrar por estado ({@code OPEN} o {@code CLOSED})
 * @param withDifference {@code true} para turnos con diferencia distinta de cero; {@code false} para
 *        turnos sin diferencia
 */
public record CashSessionFilter(
        LocalDate from,
        LocalDate to,
        Long cashierId,
        Long terminalId,
        CashSessionStatus status,
        Boolean withDifference
) {
}
