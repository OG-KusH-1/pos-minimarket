package cl.minimarket.pos.shrinkage.web;

import cl.minimarket.pos.shrinkage.ShrinkageType;
import cl.minimarket.pos.shrinkage.application.CreateShrinkageCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Solicitud HTTP para registrar una merma manual ({@code API.md} F.3).
 */
@Schema(name = "ShrinkageRequest", description = "Datos para registrar una merma manual.")
public record ShrinkageRequest(
        @Schema(description = "Identificador del producto.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "El producto es obligatorio.")
        Long productId,

        @Schema(description = "Identificador del lote (obligatorio si el producto controla lotes).")
        Long lotId,

        @Schema(
                description = "Tipo de merma (distinto de TRANSFER_DIFFERENCE).",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "El tipo de merma es obligatorio.")
        ShrinkageType shrinkageType,

        @Schema(description = "Cantidad positiva mermada.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "La cantidad es obligatoria.")
        @DecimalMin(value = "0.001", message = "La cantidad debe ser mayor que cero.")
        BigDecimal quantity,

        @Schema(description = "Motivo de la merma (obligatorio para THEFT_LOSS e INTERNAL_CONSUMPTION).")
        @Size(max = 500, message = "El motivo no puede superar los 500 caracteres.")
        String reason,

        @Schema(description = "Identificador del archivo de evidencia (categoría SHRINKAGE_EVIDENCE).")
        Long evidenceFileId,

        @Schema(
                description = "Identificador del usuario responsable de la merma.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "El usuario responsable es obligatorio.")
        Long responsibleUserId,

        @Schema(
                description = "Instante en que ocurrió la merma (ISO-8601, no futuro).",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "La fecha de ocurrencia es obligatoria.")
        OffsetDateTime occurredAt) {

    /** Convierte el DTO de entrada al comando de aplicación. */
    public CreateShrinkageCommand toCommand() {
        return new CreateShrinkageCommand(
                productId,
                lotId,
                shrinkageType,
                quantity,
                reason,
                evidenceFileId,
                responsibleUserId,
                occurredAt == null ? null : occurredAt.toInstant());
    }
}
