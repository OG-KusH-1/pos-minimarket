package cl.minimarket.pos.inventory.web.stock;

import cl.minimarket.pos.inventory.application.query.StockQueryService.StockAlertView;

import java.util.List;

/**
 * Resumen de alertas de inventario del local activo ({@code API.md} F.3).
 */
public record StockAlertResponse(
        List<StockResponse> belowMin,
        long zeroCount,
        long expiringCount,
        List<ExpiryAlertResponse> expiring
) {

    /** Convierte una vista de alertas en DTO de respuesta. */
    public static StockAlertResponse from(StockAlertView view) {
        return new StockAlertResponse(
                view.belowMin().stream().map(StockResponse::from).toList(),
                view.zeroCount(),
                view.expiringCount(),
                view.expiring().stream().map(ExpiryAlertResponse::from).toList());
    }
}
