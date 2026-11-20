package cl.minimarket.pos.purchasing.order.web;

import cl.minimarket.pos.purchasing.PurchaseOrderOrigin;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService.PurchaseOrderDetail;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService.PurchaseOrderLineView;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService.StoreView;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderQueryService.SupplierView;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Representación HTTP detallada de una orden de compra ({@code API.md} F.3 {@code PurchaseOrderResponse}).
 */
@Schema(name = "PurchaseOrderResponse", description = "Detalle completo de una orden de compra.")
public record PurchaseOrderResponse(
        @Schema(description = "Identificador único.") Long id,
        @Schema(description = "Número correlativo.") String number,
        @Schema(description = "Local emisor.") StoreRef store,
        @Schema(description = "Proveedor.") SupplierRef supplier,
        @Schema(description = "Estado actual.") PurchaseOrderStatus status,
        @Schema(description = "Origen de la orden.") PurchaseOrderOrigin origin,
        @Schema(description = "Id de corrida de reabastecimiento si aplica.") Long replenishmentRunId,
        @Schema(description = "Fecha esperada de entrega.") LocalDate expectedDate,
        @Schema(description = "Fecha/hora UTC de envío.") Instant sentAt,
        @Schema(description = "Fecha/hora UTC de cancelación.") Instant cancelledAt,
        @Schema(description = "Motivo de cancelación.") String cancelReason,
        @Schema(description = "Total neto CLP (null sin permiso catalog:cost:read).") BigDecimal netTotal,
        @Schema(description = "Total IVA CLP (null sin permiso catalog:cost:read).") BigDecimal taxTotal,
        @Schema(description = "Total bruto CLP (null sin permiso catalog:cost:read).") BigDecimal total,
        @Schema(description = "Observaciones.") String notes,
        @Schema(description = "Líneas de la orden.") List<LineResponse> lines,
        @Schema(description = "Versión de concurrencia optimista.") Long version
) {

    /** Referencia resumida de local en la orden de compra. */
    @Schema(name = "PurchaseOrderStoreRef", description = "Local emisor de la orden.")
    public record StoreRef(
            @Schema(description = "Identificador del local.") Long id,
            @Schema(description = "Código del local.") String code,
            @Schema(description = "Nombre del local.") String name
    ) {
        static StoreRef from(StoreView view) {
            return new StoreRef(view.id(), view.code(), view.name());
        }
    }

    /** Referencia resumida de proveedor en la orden de compra. */
    @Schema(name = "PurchaseOrderSupplierRef", description = "Proveedor de la orden.")
    public record SupplierRef(
            @Schema(description = "Identificador del proveedor.") Long id,
            @Schema(description = "RUT normalizado.") String rut,
            @Schema(description = "Razón social.") String legalName,
            @Schema(description = "Nombre de fantasía.") String tradeName,
            @Schema(description = "Correo electrónico.") String email
    ) {
        static SupplierRef from(SupplierView view) {
            return new SupplierRef(view.id(), view.rut(), view.legalName(), view.tradeName(), view.email());
        }
    }

    /** Línea de detalle de una orden de compra. */
    @Schema(name = "PurchaseOrderLineResponse", description = "Línea de detalle de la orden de compra.")
    public record LineResponse(
            @Schema(description = "Identificador de la línea.") Long id,
            @Schema(description = "Identificador del producto.") Long productId,
            @Schema(description = "SKU del producto.") String sku,
            @Schema(description = "Nombre del producto.") String name,
            @Schema(description = "Unidad de compra.") String purchaseUnitName,
            @Schema(description = "Cantidad en unidades de compra.") BigDecimal qtyPurchaseUnits,
            @Schema(description = "Unidades base por unidad de compra.") BigDecimal unitsPerPurchaseUnit,
            @Schema(description = "Cantidad ordenada en unidades base.") BigDecimal qtyOrdered,
            @Schema(description = "Cantidad recibida en unidades base.") BigDecimal qtyReceived,
            @Schema(description = "Costo unitario neto por unidad base (null sin catalog:cost:read).")
            BigDecimal unitCostNet,
            @Schema(description = "Total neto CLP de la línea (null sin catalog:cost:read).")
            BigDecimal lineNetTotal
    ) {
        static LineResponse from(PurchaseOrderLineView view) {
            return new LineResponse(
                    view.id(),
                    view.productId(),
                    view.sku(),
                    view.name(),
                    view.purchaseUnitName(),
                    view.qtyPurchaseUnits(),
                    view.unitsPerPurchaseUnit(),
                    view.qtyOrdered(),
                    view.qtyReceived(),
                    view.unitCostNet(),
                    view.lineNetTotal());
        }
    }

    /** Proyecta desde el detalle del servicio de consulta. */
    public static PurchaseOrderResponse from(PurchaseOrderDetail detail) {
        return new PurchaseOrderResponse(
                detail.id(),
                detail.number(),
                StoreRef.from(detail.store()),
                SupplierRef.from(detail.supplier()),
                detail.status(),
                detail.origin(),
                detail.replenishmentRunId(),
                detail.expectedDate(),
                detail.sentAt(),
                detail.cancelledAt(),
                detail.cancelReason(),
                detail.netTotal(),
                detail.taxTotal(),
                detail.total(),
                detail.notes(),
                detail.lines().stream().map(LineResponse::from).toList(),
                detail.version());
    }
}
