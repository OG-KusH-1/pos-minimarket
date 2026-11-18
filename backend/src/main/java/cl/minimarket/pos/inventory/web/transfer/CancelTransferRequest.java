package cl.minimarket.pos.inventory.web.transfer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de cancelación de un borrador de transferencia ({@code API.md} F.2/F.3).
 *
 * @param reason motivo de cancelación (3 a 250 caracteres).
 */
@Schema(
        name = "CancelTransferRequest",
        description = "Solicitud de cancelación de un borrador de transferencia entre locales.")
public record CancelTransferRequest(
        @Schema(description = "Motivo obligatorio de la cancelación (entre 3 y 250 caracteres).")
                @NotBlank(message = "El motivo de cancelación es obligatorio")
                @Size(
                        min = 3,
                        max = 250,
                        message = "El motivo de cancelación debe tener entre 3 y 250 caracteres")
                String reason) {}
