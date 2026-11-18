package cl.minimarket.pos.inventory.web.stock;

import cl.minimarket.pos.inventory.application.query.StockQueryService.ExpiryAlertView;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alerta de lote próximo a vencer o vencido ({@code API.md} F.3).
 *
 * <p>El campo {@code valuation} se incluye únicamente si el usuario posee {@code catalog:cost:read}.
 */
public record ExpiryAlertResponse(
        Long lotId,
        Long productId,
        String sku,
        String name,
        String lotNumber,
        LocalDate expiryDate,
        long daysToExpiry,
        BigDecimal quantity,
        BigDecimal valuation
) {

    /** Convierte una vista de alerta de vencimiento en DTO. */
    public static ExpiryAlertResponse from(ExpiryAlertView view) {
        return new ExpiryAlertResponse(
                view.lotId(),
                view.productId(),
                view.sku(),
                view.name(),
                view.lotNumber(),
                view.expiryDate(),
                view.daysToExpiry(),
                view.quantity(),
                view.valuation());
    }
}
