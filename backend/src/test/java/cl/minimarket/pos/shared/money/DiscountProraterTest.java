package cl.minimarket.pos.shared.money;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DiscountProraterTest {

    private static List<Money> pesos(long... values) {
        return java.util.Arrays.stream(values).mapToObj(Money::of).toList();
    }

    @Test
    void splitsEvenlyWhenDivisible() {
        assertThat(DiscountProrater.prorate(Money.of(300), pesos(1000, 1000, 1000)))
                .containsExactlyElementsOf(pesos(100, 100, 100));
    }

    /** 100 entre tres bases iguales: 33,33 por línea; el peso restante va a la primera (empate de resto). */
    @Test
    void remainderGoesToTheFirstOnTies() {
        assertThat(DiscountProrater.prorate(Money.of(100), pesos(1000, 1000, 1000)))
                .containsExactlyElementsOf(pesos(34, 33, 33));
    }

    /** Descuento 7 sobre [10,20,30]: exactos 1,17 / 2,33 / 3,50; el peso sobrante va a la de mayor resto. */
    @Test
    void remainderGoesToTheLargestFraction() {
        assertThat(DiscountProrater.prorate(Money.of(7), pesos(10, 20, 30)))
                .containsExactlyElementsOf(pesos(1, 2, 4));
    }

    @Test
    void sumIsAlwaysExact() {
        List<Money> bases = pesos(1990, 3490, 790, 12990, 450, 0, 2750);
        for (long discount = 0; discount <= 22_000; discount += 7) {
            List<Money> shares = DiscountProrater.prorate(Money.of(discount), bases);

            assertThat(Money.sum(shares)).isEqualTo(Money.of(discount));
            for (int i = 0; i < bases.size(); i++) {
                assertThat(shares.get(i).compareTo(bases.get(i))).isLessThanOrEqualTo(0);
            }
        }
    }

    @Test
    void zeroBaseLineNeverReceivesDiscount() {
        List<Money> shares = DiscountProrater.prorate(Money.of(5), pesos(0, 10, 10));

        assertThat(shares.get(0)).isEqualTo(Money.ZERO);
    }

    @Test
    void zeroDiscountGivesZeros() {
        assertThat(DiscountProrater.prorate(Money.ZERO, pesos(100, 200)))
                .containsExactlyElementsOf(pesos(0, 0));
    }

    @Test
    void rejectsDiscountLargerThanTotalBase() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DiscountProrater.prorate(Money.of(101), pesos(50, 50)));
    }

    @Test
    void rejectsNegativeValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DiscountProrater.prorate(Money.of(-1), pesos(50, 50)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DiscountProrater.prorate(Money.of(1), pesos(50, -5)));
    }

    @Test
    void rejectsPositiveDiscountWithoutLines() {
        assertThatIllegalArgumentException().isThrownBy(() -> DiscountProrater.prorate(Money.of(1), List.of()));
    }
}
