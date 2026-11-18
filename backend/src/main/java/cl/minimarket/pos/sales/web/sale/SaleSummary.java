package cl.minimarket.pos.sales.web.sale;

import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.SaleSummaryRow;
import cl.minimarket.pos.sales.application.sale.SaleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Elemento resumido de venta para listados paginados ({@code GET /sales}, {@code API.md} F.3
 * {@code SaleSummary}).
 */
public record SaleSummary(
        Long id,
        String number,
        Instant soldAt,
        String cashierName,
        String customerName,
        BigDecimal totalPayable,
        SaleStatus status,
        List<PaymentMethod> paymentMethods
) {

    /** Convierte una fila resumida de repositorio en DTO de respuesta. */
    public static SaleSummary from(SaleSummaryRow row) {
        return new SaleSummary(
                row.id(),
                row.number(),
                row.soldAt(),
                row.cashierName(),
                row.customerName(),
                row.totalPayable(),
                row.status(),
                row.paymentMethods()
        );
    }
}
