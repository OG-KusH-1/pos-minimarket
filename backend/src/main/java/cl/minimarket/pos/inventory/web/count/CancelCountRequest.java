package cl.minimarket.pos.inventory.web.count;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de cancelación de una toma de inventario abierta o enviada ({@code API.md} F.3).
 *
 * @param reason motivo de la cancelación
 */
@Schema(name = "InventoryCountCancelRequest", description = "Motivo de cancelación de una toma de inventario")
public record CancelCountRequest(
        @NotBlank(message = "El motivo es obligatorio")
        @Size(min = 3, max = 250, message = "El motivo debe tener entre 3 y 250 caracteres")
        @Schema(description = "Motivo de cancelación (3 a 250 caracteres)", example = "Se reagenda toma para cierre de mes")
        String reason) {
}
