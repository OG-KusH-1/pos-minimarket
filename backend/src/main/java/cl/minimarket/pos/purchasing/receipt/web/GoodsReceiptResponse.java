package cl.minimarket.pos.purchasing.receipt.web;

import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.CostChangeView;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.GoodsReceiptDetail;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.GoodsReceiptLineView;
import cl.minimarket.pos.purchasing.receipt.application.GoodsReceiptQueryService.SupplierView;
import cl.minimarket.pos.purchasing.receipt.domain.GoodsReceiptStatus;
import cl.minimarket.pos.purchasing.receipt.domain.SupplierDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Representación HTTP detallada de una recepción de mercadería
 * ({@code API.md} F.3 {@code GoodsReceiptResponse}).
 */
@Schema(name = "GoodsReceiptResponse", description = "Detalle completo de una recepción de mercadería.")
public record GoodsReceiptResponse(
        @Schema(description = "Identificador único.") Long id,
        @Schema(description = "Número correlativo.") String number,
        @Schema(description = "Identificador de la orden de compra asociada (null si es sin OC).") Long purchaseOrderId,
        @Schema(description = "Número de la orden de compra asociada.") String purchaseOrderNumber,
        @Schema(description = "Proveedor.") SupplierRef supplier,
        @Schema(description = "Estado actual.") GoodsReceiptStatus status,
        @Schema(description = "Tipo de documento del proveedor.") SupplierDocumentType supplierDocumentType,
        @Schema(description = "Número de documento del proveedor.") String supplierDocumentNumber,
        @Schema(description = "Fecha/hora UTC de confirmación.") Instant receivedAt,
        @Schema(description = "Usuario que confirmó la recepción.") Long receivedBy,
        @Schema(description = "Total neto CLP aceptado (null sin permiso catalog:cost:read).") BigDecimal netTotal,
        @Schema(description = "Observaciones.") String notes,
        @Schema(description = "Líneas de la recepción.") List<LineResponse> lines,
        @Schema(description = "Cambios de costo promedio generados al confirmar.") List<CostChangeResponse> costChanges,
        @Schema(description = "Versión de concurrencia optimista.") Long version
) {

    /** Referencia resumida de proveedor en la recepción. */
    @Schema(name = "GoodsReceiptSupplierRef", description = "Proveedor de la recepción.")
    public record SupplierRef(
            @Schema(description = "Identificador del proveedor.") Long id,
            @Schema(description = "RUT normalizado.") String rut,
            @Schema(description = "Razón social.") String legalName,
            @Schema(description = "Nombre de fantasía.") String tradeName
    ) {
        static SupplierRef from(SupplierView view) {
            return new SupplierRef(view.id(), view.rut(), view.legalName(), view.tradeName());
        }
    }

    /** Línea de detalle de una recepción de mercadería. */
    @Schema(name = "GoodsReceiptLineResponse", description = "Línea de detalle de la recepción de mercadería.")
    public record LineResponse(
            @Schema(description = "Identificador de la línea.") Long id,
            @Schema(description = "Identificador de la línea de orden de compra asociada.") Long purchaseOrderLineId,
            @Schema(description = "Identificador del producto.") Long productId,
            @Schema(description = "SKU del producto.") String sku,
            @Schema(description = "Nombre del producto.") String name,
            @Schema(description = "Cantidad esperada según la OC.") BigDecimal qtyExpected,
            @Schema(description = "Cantidad recibida bruta.") BigDecimal qtyReceived,
            @Schema(description = "Cantidad rechazada.") BigDecimal qtyRejected,
            @Schema(description = "Motivo de diferencia.") String differenceReason,
            @Schema(description = "Costo unitario neto (null sin catalog:cost:read).") BigDecimal unitCostNet,
            @Schema(description = "Número de lote ingresado.") String lotNumber,
            @Schema(description = "Fecha de vencimiento del lote.") LocalDate expiryDate,
            @Schema(description = "Identificador del lote creado o actualizado al confirmar.") Long lotId
    ) {
        static LineResponse from(GoodsReceiptLineView view) {
            return new LineResponse(
                    view.id(),
                    view.purchaseOrderLineId(),
                    view.productId(),
                    view.sku(),
                    view.name(),
                    view.qtyExpected(),
                    view.qtyReceived(),
                    view.qtyRejected(),
                    view.differenceReason(),
                    view.unitCostNet(),
                    view.lotNumber(),
                    view.expiryDate(),
                    view.lotId());
        }
    }

    /** Cambio de costo promedio ponderado resultante de la confirmación. */
    @Schema(name = "GoodsReceiptCostChangeResponse", description = "Cambio de costo promedio ponderado por producto.")
    public record CostChangeResponse(
            @Schema(description = "Identificador del producto.") Long productId,
            @Schema(description = "Costo promedio anterior (null sin catalog:cost:read).") BigDecimal oldAvgCost,
            @Schema(description = "Nuevo costo promedio (null sin catalog:cost:read).") BigDecimal newAvgCost
    ) {
        static CostChangeResponse from(CostChangeView view) {
            return new CostChangeResponse(view.productId(), view.oldAvgCost(), view.newAvgCost());
        }
    }

    /** Proyecta desde el detalle del servicio de consulta. */
    public static GoodsReceiptResponse from(GoodsReceiptDetail detail) {
        return new GoodsReceiptResponse(
                detail.id(),
                detail.number(),
                detail.purchaseOrderId(),
                detail.purchaseOrderNumber(),
                SupplierRef.from(detail.supplier()),
                detail.status(),
                detail.supplierDocumentType(),
                detail.supplierDocumentNumber(),
                detail.receivedAt(),
                detail.receivedBy(),
                detail.netTotal(),
                detail.notes(),
                detail.lines().stream().map(LineResponse::from).toList(),
                detail.costChanges().stream().map(CostChangeResponse::from).toList(),
                detail.version());
    }
}
