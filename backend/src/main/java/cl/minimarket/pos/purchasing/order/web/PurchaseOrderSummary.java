package cl.minimarket.pos.purchasing.order.web;

import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService.PurchaseOrderSummaryView;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderOrigin;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Resumen paginado de orden de compra ({@code API.md} F.3 {@code PurchaseOrderSummary}).
 */
@Schema(name = "PurchaseOrderSummary", description = "Resumen de orden de compra para listados.")
public record PurchaseOrderSummary(
        @Schema(description = "Identificador de la orden.") Long id,
        @Schema(description = "Número correlativo de la orden.") String number,
        @Schema(description = "Razón social del proveedor.") String supplierName,
        @Schema(description = "Estado actual de la orden.") PurchaseOrderStatus status,
        @Schema(description = "Origen de la orden.") PurchaseOrderOrigin origin,
        @Schema(description = "Total bruto CLP (null sin permiso catalog:cost:read).") BigDecimal total,
        @Schema(description = "Fecha/hora UTC de creación.") Instant createdAt,
        @Schema(description = "Fecha esperada de entrega.") LocalDate expectedDate
) {
    /** Proyecta desde la vista de consulta. */
    public static PurchaseOrderSummary from(PurchaseOrderSummaryView view) {
        return new PurchaseOrderSummary(
                view.id(),
                view.number(),
                view.supplierName(),
                view.status(),
                view.origin(),
                view.total(),
                view.createdAt(),
                view.expectedDate());
    }
}
