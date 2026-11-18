package cl.minimarket.pos.purchasing.order.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Solicitud de envío/aprobación de una orden de compra ({@code API.md} F.3).
 */
@Schema(name = "SendPurchaseOrderRequest", description = "Datos para aprobar y enviar una orden de compra.")
public record SendPurchaseOrderRequest(
        @Schema(description = "Si es true, encola el PDF por correo al proveedor en la outbox.")
        Boolean emailToSupplier,

        @Schema(description = "Versión de concurrencia optimista.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Long version
) {
    public boolean shouldEmailSupplier() {
        return Boolean.TRUE.equals(emailToSupplier);
    }
}
