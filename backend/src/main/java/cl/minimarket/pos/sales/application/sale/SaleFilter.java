package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.sales.application.pricing.PaymentMethod;

import java.time.LocalDate;

/**
 * Filtros soportados para listar ventas en {@code GET /sales}.
 *
 * @param from          fecha comercial inicial inclusiva
 * @param to            fecha comercial final inclusiva
 * @param cashierId     identificador de cajero
 * @param terminalId    identificador de terminal
 * @param status        estado de la venta
 * @param paymentMethod medio de pago presente en la venta
 * @param customerId    identificador de cliente asociado
 * @param q             búsqueda parcial por número de venta
 */
public record SaleFilter(
        LocalDate from,
        LocalDate to,
        Long cashierId,
        Long terminalId,
        SaleStatus status,
        PaymentMethod paymentMethod,
        Long customerId,
        String q
) {
}
