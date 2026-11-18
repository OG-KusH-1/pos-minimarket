package cl.minimarket.pos.shared.money;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Prorrateo del descuento al total entre las líneas de una venta (A.5), por mayor resto.
 *
 * <p>El descuento se reparte proporcionalmente a la base de cada línea
 * ({@code line_subtotal_gross − descuentos de línea}) <b>antes</b> de calcular el neto:
 * <pre>
 *   exact_i = discount × base_i / Σ base
 *   share_i = floor(exact_i)                       (parte entera)
 *   resto   = discount − Σ share_i                 (pesos que faltan por asignar)
 *   los {@code resto} pesos se entregan de a uno a las líneas con mayor parte fraccionaria
 *   (en empate, a la de menor índice, para que el resultado sea determinista)
 * </pre>
 * Así {@code Σ share_i = discount} exactamente y ninguna línea se desvía más de 1 peso de su
 * proporción exacta. Se usa aritmética entera ({@link BigInteger}) para evitar cualquier error
 * de redondeo: {@code discount × base} puede exceder el rango de {@code long}.
 */
public final class DiscountProrater {

    private DiscountProrater() {
    }

    /**
     * Prorratea un descuento entre las bases de las líneas.
     *
     * @param discount descuento al total (≥ 0)
     * @param bases base de cada línea (≥ 0), en el orden de las líneas de la venta
     * @return parte del descuento de cada línea, en el mismo orden; la suma es exactamente
     *         {@code discount}; lista vacía si no hay líneas y el descuento es 0
     * @throws IllegalArgumentException si el descuento o alguna base es negativa, si no hay líneas
     *         con un descuento positivo, o si el descuento excede la suma de las bases
     */
    public static List<Money> prorate(Money discount, List<Money> bases) {
        if (discount.isNegative() || bases.stream().anyMatch(Money::isNegative)) {
            throw new IllegalArgumentException("El descuento y las bases no pueden ser negativos");
        }
        Money totalBase = Money.sum(bases);
        if (discount.isZero()) {
            return bases.stream().map(b -> Money.ZERO).toList();
        }
        if (discount.compareTo(totalBase) > 0) {
            throw new IllegalArgumentException("El descuento excede la suma de las bases de las líneas");
        }

        BigInteger discountUnits = discount.amount().toBigIntegerExact();
        BigInteger totalUnits = totalBase.amount().toBigIntegerExact();

        long[] shares = new long[bases.size()];
        BigInteger[] remainders = new BigInteger[bases.size()];
        long assigned = 0;
        for (int i = 0; i < shares.length; i++) {
            BigInteger[] divided = discountUnits
                    .multiply(bases.get(i).amount().toBigIntegerExact())
                    .divideAndRemainder(totalUnits);
            shares[i] = divided[0].longValueExact();
            remainders[i] = divided[1];
            assigned += shares[i];
        }

        long pending = discount.toLong() - assigned;
        List<Integer> byRemainderDesc = new ArrayList<>(IntStream.range(0, shares.length).boxed().toList());
        byRemainderDesc.sort(Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
                .thenComparing(Comparator.naturalOrder()));
        for (int k = 0; k < pending; k++) {
            shares[byRemainderDesc.get(k)]++;
        }
        return IntStream.range(0, shares.length).mapToObj(i -> Money.of(shares[i])).toList();
    }
}
