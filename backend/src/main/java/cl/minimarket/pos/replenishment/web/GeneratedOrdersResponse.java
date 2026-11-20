package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.replenishment.application.run.ReplenishmentDraftService.GeneratedOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta de generación de borradores de OC ({@code API.md} F.3 {@code GeneratedOrdersResponse}).
 */
@Schema(name = "GeneratedOrdersResponse", description = "Borradores de OC creados desde la corrida.")
public record GeneratedOrdersResponse(
        @Schema(description = "Órdenes creadas, una por proveedor.")
        List<PurchaseOrderRef> purchaseOrders
) {

    /** Resumen de una orden creada. */
    @Schema(name = "GeneratedPurchaseOrder", description = "Borrador de OC creado.")
    public record PurchaseOrderRef(
            @Schema(description = "Identificador de la orden.") Long id,
            @Schema(description = "Folio de la orden.") String number,
            @Schema(description = "Proveedor de la orden.") Long supplierId,
            @Schema(description = "Nombre del proveedor.") String supplierName,
            @Schema(description = "Líneas de sugerencia incluidas.") int linesCount,
            @Schema(description = "Total neto (CLP).") BigDecimal netTotal
    ) {

        /** Proyecta desde la vista de servicio. */
        public static PurchaseOrderRef from(GeneratedOrder order) {
            return new PurchaseOrderRef(
                    order.id(),
                    order.number(),
                    order.supplierId(),
                    order.supplierName(),
                    order.linesCount(),
                    order.netTotal());
        }
    }

    /** Proyecta la lista completa. */
    public static GeneratedOrdersResponse from(List<GeneratedOrder> orders) {
        return new GeneratedOrdersResponse(orders.stream().map(PurchaseOrderRef::from).toList());
    }
}
