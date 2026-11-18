package cl.minimarket.pos.inventory.web.lot;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta HTTP con la sugerencia de consumo FEFO (First-Expired, First-Out) para un producto en
 * el local activo y el eventual faltante no cubierto por los lotes disponibles ({@code API.md} F.3,
 * {@code HU-INV-09}).
 */
@Schema(description = "Sugerencia de asignación FEFO por lote y cantidad faltante.")
public record FefoSuggestionResponse(
        @Schema(description = "Identificador del producto consultado.")
        Long productId,

        @Schema(description = "Cantidad total solicitada en unidad base.")
        BigDecimal requestedQty,

        @Schema(description = "Asignaciones sugeridas ordenadas por vencimiento más próximo (NULLS LAST).")
        List<AllocationResponse> allocations,

        @Schema(description = "Cantidad faltante no cubierta por los lotes con saldo disponible (0 si se cubre todo).")
        BigDecimal shortage
) {

    /** Copia defensiva de la lista de asignaciones. */
    public FefoSuggestionResponse {
        allocations = List.copyOf(allocations);
    }

    /**
     * Porción asignada desde un lote específico en orden FEFO.
     */
    @Schema(description = "Asignación sugerida sobre un lote específico.")
    public record AllocationResponse(
            @Schema(description = "Identificador del lote.")
            Long lotId,

            @Schema(description = "Código o número del lote.")
            String lotNumber,

            @Schema(description = "Fecha de vencimiento del lote (null si no tiene).")
            LocalDate expiryDate,

            @Schema(description = "Cantidad sugerida a consumir desde este lote.")
            BigDecimal quantity
    ) {
    }
}
