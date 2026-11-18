package cl.minimarket.pos.purchasing.order.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de cancelación de una orden de compra ({@code API.md} F.3 {@code CancelRequest}).
 */
@Schema(name = "PurchaseOrderCancelRequest", description = "Motivo de cancelación de la orden de compra.")
public record CancelPurchaseOrderRequest(
        @Schema(description = "Motivo de cancelación (3 a 250 caracteres).", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 3, max = 250)
        String reason
) {
}
