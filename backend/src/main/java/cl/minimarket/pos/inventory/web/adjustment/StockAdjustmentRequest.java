package cl.minimarket.pos.inventory.web.adjustment;

import cl.minimarket.pos.inventory.application.adjustment.AdjustmentReason;
import cl.minimarket.pos.inventory.application.adjustment.CreateAdjustmentCommand;
import cl.minimarket.pos.inventory.application.adjustment.CreateAdjustmentCommand.LineCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Solicitud HTTP para crear un ajuste manual de stock pendiente de aprobación
 * ({@code API.md} F.3).
 */
@Schema(description = "Solicitud para crear un ajuste manual de stock (estado inicial PENDING_APPROVAL).")
public record StockAdjustmentRequest(
        @Schema(description = "Motivo tipificado del ajuste.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "El motivo del ajuste es obligatorio.")
        AdjustmentReason reasonCode,

        @Schema(description = "Observaciones o respaldo operativo del ajuste (máximo 500 caracteres).")
        @Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres.")
        String notes,

        @Schema(description = "Líneas de productos a ajustar (entre 1 y 200).", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "Debe incluir al menos una línea de ajuste.")
        @Size(min = 1, max = 200, message = "El ajuste debe incluir entre 1 y 200 líneas.")
        List<@Valid LineRequest> lines
) {

    /**
     * Línea individual de ajuste de stock.
     */
    @Schema(name = "StockAdjustmentLineRequest", description = "Línea de ajuste para un producto (y lote cuando corresponda).")
    public record LineRequest(
            @Schema(description = "Identificador del producto.", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "El producto es obligatorio.")
            Long productId,

            @Schema(
                    description = "Variación de cantidad: positiva para entrada, negativa para salida (distinta de cero).",
                    requiredMode = Schema.RequiredMode.REQUIRED
            )
            @NotNull(message = "La variación de cantidad es obligatoria.")
            BigDecimal quantityDelta,

            @Schema(description = "Identificador del lote (obligatorio si el producto controla lotes y quantityDelta < 0).")
            Long lotId,

            @Schema(description = "Número de lote para entradas de productos con control de lotes cuando no se indica lotId.")
            @Size(max = 40, message = "El número de lote no puede superar los 40 caracteres.")
            String lotNumber,

            @Schema(description = "Fecha de vencimiento del lote (obligatoria en entradas con lote si el producto tiene shelfLifeDays).")
            LocalDate expiryDate
    ) {
    }

    /** Convierte el DTO de entrada en comando de aplicación. */
    public CreateAdjustmentCommand toCommand() {
        List<LineCommand> lineCommands = lines == null
                ? List.of()
                : lines.stream()
                        .map(l -> new LineCommand(
                                l.productId(),
                                l.quantityDelta(),
                                l.lotId(),
                                l.lotNumber(),
                                l.expiryDate()))
                        .toList();
        return new CreateAdjustmentCommand(reasonCode, notes, lineCommands);
    }
}
