package cl.minimarket.pos.shrinkage.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud HTTP para rechazar una merma pendiente ({@code API.md} F.3).
 */
@Schema(name = "ShrinkageRejectRequest", description = "Motivo de rechazo de una merma pendiente.")
public record ShrinkageRejectRequest(
        @Schema(
                description = "Motivo explícito del rechazo (entre 3 y 250 caracteres).",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El motivo del rechazo es obligatorio.")
        @Size(min = 3, max = 250, message = "El motivo del rechazo debe tener entre 3 y 250 caracteres.")
        String reason) {
}
