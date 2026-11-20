package cl.minimarket.pos.sales.application.returns;

import java.time.LocalDate;

/**
 * Filtros de búsqueda para el listado paginado de anulaciones y devoluciones ({@code GET /sale-returns}).
 */
public record SaleReturnFilter(
        LocalDate from,
        LocalDate to,
        SaleReturnType returnType,
        Long saleId
) {
}
