package cl.minimarket.pos.inventory.web.count;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de rechazo de una toma de inventario enviada a revisión ({@code API.md} F.3).
 *
 * @param reason motivo del rechazo para devolver la toma a reconteo
 */
@Schema(name = "InventoryCountRejectRequest", description = "Motivo de rechazo de una toma de inventario")
public record RejectCountRequest(
        @NotBlank(message = "El motivo es obligatorio")
        @Size(min = 3, max = 250, message = "El motivo debe tener entre 3 y 250 caracteres")
        @Schema(description = "Motivo del rechazo (3 a 250 caracteres)", example = "Revisar conteo de pasillo lácteos")
        String reason) {
}
