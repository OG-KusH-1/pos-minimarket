package cl.minimarket.pos.inventory.application.adjustment;

import java.time.LocalDate;

/**
 * Criterios opcionales para filtrar el listado paginado de ajustes de stock ({@code API.md} F.2).
 *
 * @param status estado del ajuste ({@code PENDING_APPROVAL}, {@code APPROVED} o {@code REJECTED})
 * @param from   fecha de inicio inclusive en zona de negocio (sobre {@code created_at})
 * @param to     fecha de término inclusive en zona de negocio (sobre {@code created_at})
 */
public record StockAdjustmentFilter(
        AdjustmentStatus status,
        LocalDate from,
        LocalDate to
) {
}
