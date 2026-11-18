package cl.minimarket.pos.inventory.web.count;

import cl.minimarket.pos.inventory.application.count.CountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de apertura de una nueva toma física de inventario ({@code API.md} F.3).
 *
 * @param countType tipo de toma ({@code FULL} o {@code CYCLIC})
 * @param categoryId categoría raíz del alcance cíclico (obligatoria si {@code CYCLIC}, nula si {@code FULL})
 * @param blind {@code true} para ocultar existencias teóricas a quienes ejecutan sin permiso de aprobar
 * @param notes observaciones opcionales de la toma
 */
@Schema(description = "Parámetros para iniciar una toma física de inventario")
public record InventoryCountRequest(
        @NotNull(message = "El tipo de toma es obligatorio")
        @Schema(description = "Tipo de toma: FULL o CYCLIC", example = "CYCLIC")
        CountType countType,

        @Schema(description = "ID de categoría raíz (obligatorio si countType=CYCLIC, nulo si FULL)", example = "5")
        Long categoryId,

        @Schema(description = "Indica si la toma es ciega (por defecto true)", example = "true")
        Boolean blind,

        @Size(max = 2000, message = "Las notas no pueden superar 2000 caracteres")
        @Schema(description = "Notas u observaciones opcionales")
        String notes) {
}
