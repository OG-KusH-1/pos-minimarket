package cl.minimarket.pos.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Desglose de neto e impuestos de una línea a partir de su bruto con impuestos incluidos (A.5).
 *
 * <p>Fórmulas (todas con redondeo HALF_UP a escala 0):
 * <pre>
 *   R         = Σ rate_i / 100                  (tasas de los impuestos que se desglosan)
 *   net       = round(gross / (1 + R), 0)
 *   tax_total = gross − net
 *   tax_i     = round(net × rate_i / 100, 0)    (el último impuesto, ordenado por id,
 *                                                recibe la diferencia: Σ tax_i = tax_total)
 * </pre>
 * Los totales del documento son la suma de las líneas (no se recalculan sobre el total).
 *
 * <p><b>Productos exentos ({@code vat_exempt}).</b> El llamador pasa solo los impuestos que
 * realmente gravan al producto: un producto exento no incluye el IVA en su lista (así lo exige
 * {@code PUT /products/{id}/taxes}).
 * <ul>
 *   <li>Exento sin otros impuestos: {@code line_exempt = gross} y {@code net = tax = 0}.</li>
 *   <li>Exento con un impuesto adicional (ILA, IABA): se desglosa solo ese impuesto con la
 *       fórmula anterior ({@code R} sin el IVA) y {@code line_exempt = 0}.</li>
 *   <li>No exento y sin impuestos: {@code net = gross}, sin impuestos ni exento.</li>
 * </ul>
 *
 * <p>Ejemplo (HU-CAT-07): bebida con IVA 19 % e IABA 18 % a $1.370: {@code R = 0,37},
 * {@code net = round(1370 / 1,37) = 1.000}, IVA 190, IABA 180, suma 370.
 *
 * <p>Si la última tasa es muy baja respecto a las demás, la diferencia absorbida puede dejar su
 * monto algo distinto del redondeo propio (a lo sumo ±1 por impuesto previo): es el comportamiento
 * definido por el plan para que la suma cuadre exactamente.
 */
public final class TaxBreakdownCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private TaxBreakdownCalculator() {
    }

    /**
     * Impuesto aplicable a una línea.
     *
     * @param taxId identificador del impuesto (define el orden: el mayor id absorbe la diferencia)
     * @param ratePercent tasa en porcentaje (p. ej. {@code 19.0000}); no negativa
     */
    public record TaxRate(long taxId, BigDecimal ratePercent) {
        /**
         * @throws NullPointerException si la tasa es nula
         * @throws IllegalArgumentException si la tasa es negativa
         */
        public TaxRate {
            Objects.requireNonNull(ratePercent, "ratePercent");
            if (ratePercent.signum() < 0) {
                throw new IllegalArgumentException("La tasa de impuesto no puede ser negativa");
            }
        }
    }

    /**
     * Monto de un impuesto dentro del desglose.
     *
     * @param taxId identificador del impuesto
     * @param amount monto del impuesto (escala 0)
     */
    public record TaxAmount(long taxId, Money amount) {
    }

    /**
     * Resultado del desglose de una línea. Invariante: {@code net + taxTotal + exempt = gross} y
     * {@code Σ taxes.amount = taxTotal}.
     *
     * @param gross bruto de la línea con impuestos incluidos
     * @param net neto afecto
     * @param taxTotal suma de los impuestos
     * @param exempt monto exento ({@code line_exempt})
     * @param taxes impuestos ordenados por id (inmutable)
     */
    public record Breakdown(Money gross, Money net, Money taxTotal, Money exempt, List<TaxAmount> taxes) {
        /** Copia defensiva de la lista de impuestos. */
        public Breakdown {
            taxes = List.copyOf(taxes);
        }
    }

    /**
     * Calcula el desglose de una línea.
     *
     * @param gross bruto de la línea con impuestos incluidos (después de descuentos y prorrateo)
     * @param taxes impuestos que gravan la línea; puede estar vacío
     * @param vatExempt si el producto es exento de IVA (solo cambia el resultado cuando
     *                  {@code taxes} está vacío)
     * @return desglose con neto, impuestos y exento
     * @throws NullPointerException si algún argumento es nulo
     */
    public static Breakdown calculate(Money gross, List<TaxRate> taxes, boolean vatExempt) {
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(taxes, "taxes");

        if (taxes.isEmpty()) {
            Money exempt = vatExempt ? gross : Money.ZERO;
            Money net = vatExempt ? Money.ZERO : gross;
            return new Breakdown(gross, net, Money.ZERO, exempt, List.of());
        }

        List<TaxRate> ordered = taxes.stream().sorted(Comparator.comparingLong(TaxRate::taxId)).toList();
        BigDecimal combinedRate = ordered.stream()
                .map(t -> t.ratePercent().divide(HUNDRED))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Money net = Money.of(gross.amount().divide(BigDecimal.ONE.add(combinedRate), Rounding.CLP_SCALE, RoundingMode.HALF_UP));
        Money taxTotal = gross.minus(net);

        return new Breakdown(gross, net, taxTotal, Money.ZERO, distribute(net, taxTotal, ordered));
    }

    /** Reparte {@code taxTotal} entre los impuestos: redondeo propio y el último absorbe la diferencia. */
    private static List<TaxAmount> distribute(Money net, Money taxTotal, List<TaxRate> ordered) {
        TaxAmount[] amounts = new TaxAmount[ordered.size()];
        Money assigned = Money.ZERO;
        int last = ordered.size() - 1;
        for (int i = 0; i < last; i++) {
            TaxRate tax = ordered.get(i);
            Money amount = net.times(tax.ratePercent().divide(HUNDRED));
            amounts[i] = new TaxAmount(tax.taxId(), amount);
            assigned = assigned.plus(amount);
        }
        amounts[last] = new TaxAmount(ordered.get(last).taxId(), taxTotal.minus(assigned));
        return List.of(amounts);
    }
}
