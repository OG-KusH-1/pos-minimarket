package cl.minimarket.pos.purchasing.receipt.domain;

import cl.minimarket.pos.shared.money.Rounding;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Cálculo puro del costo promedio ponderado global por producto en recepciones de compra
 * ({@code PLAN.md} A.5, {@code HU-PRV-07}).
 *
 * <p>Fórmula:
 * <ul>
 *   <li>{@code q_in = qty_received - qty_rejected} (lo rechazado no entra al stock ni al costo).</li>
 *   <li>Si {@code q_in <= 0}, el costo promedio vigente no cambia.</li>
 *   <li>Si {@code Q <= 0}, {@code avg' = round(c_in, 4)}.</li>
 *   <li>En caso contrario, {@code avg' = round((Q * avg + q_in * c_in) / (Q + q_in), 4)} HALF_UP.</li>
 * </ul>
 */
public final class AverageCostCalculator {

    private AverageCostCalculator() {
    }

    /**
     * Calcula el nuevo costo promedio ponderado tras una línea de recepción.
     *
     * @param companyQuantityBefore existencia total en la empresa (todos los locales + tránsito) antes de la entrada
     * @param currentAvgCost        costo promedio vigente antes de la entrada
     * @param qtyReceived           cantidad recibida bruta
     * @param qtyRejected           cantidad rechazada (0..qtyReceived)
     * @param unitCostNet           costo unitario neto de entrada
     * @return nuevo costo promedio ponderado (escala 4, {@code HALF_UP})
     */
    public static BigDecimal calculate(BigDecimal companyQuantityBefore,
                                       BigDecimal currentAvgCost,
                                       BigDecimal qtyReceived,
                                       BigDecimal qtyRejected,
                                       BigDecimal unitCostNet) {
        Objects.requireNonNull(companyQuantityBefore, "companyQuantityBefore");
        Objects.requireNonNull(currentAvgCost, "currentAvgCost");
        Objects.requireNonNull(qtyReceived, "qtyReceived");
        Objects.requireNonNull(qtyRejected, "qtyRejected");
        Objects.requireNonNull(unitCostNet, "unitCostNet");

        BigDecimal qIn = qtyReceived.subtract(qtyRejected, MathContext.DECIMAL64);
        if (qIn.compareTo(BigDecimal.ZERO) <= 0) {
            return Rounding.unitCost(currentAvgCost);
        }
        if (companyQuantityBefore.compareTo(BigDecimal.ZERO) <= 0) {
            return Rounding.unitCost(unitCostNet);
        }
        BigDecimal totalValueBefore = companyQuantityBefore.multiply(currentAvgCost, MathContext.DECIMAL64);
        BigDecimal inboundValue = qIn.multiply(unitCostNet, MathContext.DECIMAL64);
        BigDecimal totalQtyAfter = companyQuantityBefore.add(qIn, MathContext.DECIMAL64);
        return Rounding.unitCost(totalValueBefore.add(inboundValue, MathContext.DECIMAL64)
                .divide(totalQtyAfter, MathContext.DECIMAL64));
    }
}
