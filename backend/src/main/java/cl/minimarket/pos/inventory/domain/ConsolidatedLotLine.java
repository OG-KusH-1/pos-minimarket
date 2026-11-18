package cl.minimarket.pos.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Representa un sub-renglón consolidado por lote dentro de una línea de producto de un comando
 * de inventario ({@link ConsolidatedStockLine}).
 *
 * <p>Permite distinguir entre asignaciones explícitas (por {@code lotId} o por el par
 * {@code lotNumber} + {@code expiryDate}) y asignaciones automáticas sin especificar lote
 * (consumo FEFO en salidas de productos controlados por lote o productos sin control de lotes).</p>
 */
public record ConsolidatedLotLine(
        Long lotId,
        String lotNumber,
        LocalDate expiryDate,
        BigDecimal quantity,
        BigDecimal unitCost) {

    public ConsolidatedLotLine {
        Objects.requireNonNull(quantity, "quantity");
        if (lotNumber != null) {
            String trimmed = lotNumber.trim();
            lotNumber = trimmed.isEmpty() ? null : trimmed;
        }
    }

    /**
     * Indica si la sub-línea identifica un lote concreto mediante {@code lotId} o {@code lotNumber}.
     *
     * @return {@code true} cuando hay identificador explícito de lote
     */
    public boolean hasExplicitLotTarget() {
        return lotId != null || lotNumber != null;
    }

    /**
     * Indica si la sub-línea contiene algún dato de lote ({@code lotId}, {@code lotNumber} o
     * {@code expiryDate}).
     *
     * @return {@code true} si la línea original especificó información de lote
     */
    public boolean hasLotData() {
        return lotId != null || lotNumber != null || expiryDate != null;
    }
}
