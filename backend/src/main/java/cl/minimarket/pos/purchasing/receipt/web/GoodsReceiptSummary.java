package cl.minimarket.pos.purchasing.receipt.web;

import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.GoodsReceiptSummaryView;
import cl.minimarket.pos.purchasing.receipt.domain.GoodsReceiptStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Resumen paginado de recepciones de mercadería ({@code API.md} F.3 {@code GoodsReceiptSummary}).
 */
@Schema(name = "GoodsReceiptSummary", description = "Resumen de una recepción de mercadería para listados.")
public record GoodsReceiptSummary(
        @Schema(description = "Identificador único.") Long id,
        @Schema(description = "Número correlativo.") String number,
        @Schema(description = "Nombre legal del proveedor.") String supplierName,
        @Schema(description = "Número de la orden de compra asociada.") String purchaseOrderNumber,
        @Schema(description = "Estado actual.") GoodsReceiptStatus status,
        @Schema(description = "Fecha/hora UTC de recepción confirmada.") Instant receivedAt,
        @Schema(description = "Total neto CLP (null sin permiso catalog:cost:read).") BigDecimal netTotal
) {
    /** Proyecta desde la vista de consulta. */
    public static GoodsReceiptSummary from(GoodsReceiptSummaryView view) {
        return new GoodsReceiptSummary(
                view.id(),
                view.number(),
                view.supplierName(),
                view.purchaseOrderNumber(),
                view.status(),
                view.receivedAt(),
                view.netTotal());
    }
}
