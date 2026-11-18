package cl.minimarket.pos.inventory.web.adjustment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud para rechazar un ajuste de stock pendiente ({@code API.md} F.3).
 */
@Schema(name = "StockAdjustmentRejectRequest", description = "Solicitud para rechazar un ajuste manual de stock pendiente.")
public record RejectRequest(
        @Schema(
                description = "Motivo explícito del rechazo (entre 3 y 250 caracteres).",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "El motivo del rechazo es obligatorio.")
        @Size(min = 3, max = 250, message = "El motivo del rechazo debe tener entre 3 y 250 caracteres.")
        String reason
) {
}
