package cl.minimarket.pos.purchasing.order.web;

import cl.minimarket.pos.purchasing.order.application.PurchaseOrderService.LineCommand;
import cl.minimarket.pos.purchasing.order.application.PurchaseOrderService.SaveCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cuerpo de creación o actualización de una orden de compra ({@code API.md} F.3).
 */
@Schema(name = "PurchaseOrderRequest", description = "Datos para crear o actualizar una orden de compra.")
public record PurchaseOrderRequest(
        @Schema(description = "Identificador del proveedor.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Long supplierId,

        @Schema(description = "Fecha esperada de entrega (igual o posterior a hoy).")
        LocalDate expectedDate,

        @Schema(description = "Observaciones de la orden.")
        @Size(max = 2000)
        String notes,

        @Schema(description = "Líneas de la orden (1 a 500 productos sin repetir).", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty
        @Size(min = 1, max = 500)
        @Valid
        List<LineRequest> lines,

        @Schema(description = "Versión de concurrencia optimista (obligatoria al actualizar).")
        Long version
) {

    /** Línea de entrada de una orden de compra. */
    @Schema(name = "PurchaseOrderLineRequest", description = "Línea de producto en una orden de compra.")
    public record LineRequest(
            @Schema(description = "Identificador del producto.", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            Long productId,

            @Schema(description = "Cantidad en unidades de compra (> 0).", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            @DecimalMin(value = "0.001")
            BigDecimal qtyPurchaseUnits,

            @Schema(description = "Costo unitario neto por unidad base (opcional si existe asociación producto-proveedor).")
            @DecimalMin(value = "0")
            BigDecimal unitCostNet
    ) {
        LineCommand toCommand() {
            return new LineCommand(productId, qtyPurchaseUnits, unitCostNet);
        }
    }

    /** Convierte el DTO HTTP en comando de aplicación. */
    public SaveCommand toCommand() {
        return new SaveCommand(
                supplierId,
                expectedDate,
                notes,
                lines == null ? List.of() : lines.stream().map(LineRequest::toCommand).toList(),
                version);
    }
}
