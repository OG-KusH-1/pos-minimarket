package cl.minimarket.pos.inventory.application.adjustment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Comando de aplicación para solicitar un nuevo ajuste manual de stock en estado
 * {@link AdjustmentStatus#PENDING_APPROVAL}.
 *
 * @param reasonCode motivo del ajuste (obligatorio)
 * @param notes      observaciones adicionales (hasta 500 caracteres)
 * @param lines      líneas del ajuste (entre 1 y 200)
 */
public record CreateAdjustmentCommand(
        AdjustmentReason reasonCode,
        String notes,
        List<LineCommand> lines
) {

    public CreateAdjustmentCommand {
        Objects.requireNonNull(reasonCode, "reasonCode");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * Línea individual solicitada para ajuste de stock.
     *
     * @param productId     identificador del producto
     * @param quantityDelta variación de cantidad (positiva para entrada, negativa para salida; != 0)
     * @param lotId         identificador del lote (obligatorio si controla lotes y delta &lt; 0)
     * @param lotNumber     número de lote (para productos con lote y delta &gt; 0 si no viene lotId)
     * @param expiryDate    fecha de vencimiento del lote (para entradas con lote si aplica)
     */
    public record LineCommand(
            Long productId,
            BigDecimal quantityDelta,
            Long lotId,
            String lotNumber,
            LocalDate expiryDate
    ) {
    }
}
