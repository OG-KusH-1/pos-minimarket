package cl.minimarket.pos.cash;

import java.time.LocalDate;

/**
 * Referencia pública e inmutable a un turno de caja ({@code PLAN.md} A.4).
 *
 * @param id identificador del turno en {@code cash_sessions}
 * @param storeId identificador del local
 * @param terminalId identificador de la terminal POS
 * @param cashierUserId identificador del cajero dueño del turno
 * @param number folio correlativo del turno (ej. {@code L01-TU-00000001})
 * @param businessDate fecha de negocio de apertura
 */
public record CashSessionRef(
        Long id,
        Long storeId,
        Long terminalId,
        Long cashierUserId,
        String number,
        LocalDate businessDate
) {
}
