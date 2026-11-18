package cl.minimarket.pos.inventory.web.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Solicitud HTTP para corregir el número y/o la fecha de vencimiento de un lote existente
 * ({@code API.md} F.3).
 */
@Schema(description = "Solicitud para corregir número de lote y/o fecha de vencimiento.")
public record LotUpdateRequest(
        @Schema(
                description = "Nuevo número de lote (1 a 40 caracteres).",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "El número de lote es obligatorio.")
        @Size(min = 1, max = 40, message = "El número de lote debe tener entre 1 y 40 caracteres.")
        String lotNumber,

        @Schema(description = "Nueva fecha de vencimiento (YYYY-MM-DD); puede ser null si no aplica.")
        LocalDate expiryDate,

        @Schema(
                description = "Motivo de la corrección para trazabilidad en auditoría (3 a 250 caracteres).",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "El motivo de la corrección es obligatorio.")
        @Size(min = 3, max = 250, message = "El motivo debe tener entre 3 y 250 caracteres.")
        String reason,

        @Schema(
                description = "Versión esperada del registro para control de concurrencia optimista.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "La versión del lote es obligatoria.")
        @Min(value = 0, message = "La versión del lote debe ser mayor o igual a cero.")
        Long version
) {
}
