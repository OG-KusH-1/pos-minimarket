package cl.minimarket.pos.shared.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MoneyAndQuantityTest {

    @Test
    void moneyNormalizesToScaleZeroHalfUp() {
        assertThat(Money.of(new BigDecimal("10.0")).amount().scale()).isZero();
        assertThat(Money.of(new BigDecimal("10.5"))).isEqualTo(Money.of(11));
        assertThat(Money.of(new BigDecimal("10.4"))).isEqualTo(Money.of(10));
        assertThat(Money.of(new BigDecimal("-10.5"))).isEqualTo(Money.of(-11));
    }

    @Test
    void moneyEqualsIgnoresInputScale() {
        assertThat(Money.of(new BigDecimal("10.00"))).isEqualTo(Money.of(10)).hasSameHashCodeAs(Money.of(10));
    }

    @Test
    void moneyArithmetic() {
        assertThat(Money.of(5).plus(Money.of(7))).isEqualTo(Money.of(12));
        assertThat(Money.of(5).minus(Money.of(7))).isEqualTo(Money.of(-2));
        assertThat(Money.of(5).negate().isNegative()).isTrue();
        assertThat(Money.sum(List.of(Money.of(1), Money.of(2), Money.of(3)))).isEqualTo(Money.of(6));
        assertThat(Money.sum(List.of())).isEqualTo(Money.ZERO);
        assertThat(Money.of(3)).isLessThan(Money.of(4));
        assertThat(Money.of(1234).toString()).isEqualTo("1234");
        assertThat(Money.ZERO.isZero()).isTrue();
    }

    @Test
    void moneyRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new Money(null));
    }

    /** HU-POS-19 (1): 0,750 kg a $8.990/kg, línea = round(6742,5) = $6.743. */
    @Test
    void weighedLineIsRoundedHalfUp() {
        Money line = Money.of(8990).times(Quantity.of("0.750"));

        assertThat(line).isEqualTo(Money.of(6743));
    }

    @Test
    void timesRoundsOnlyOnceOnTheExactProduct() {
        assertThat(Money.of(333).times(Quantity.of("0.001"))).isEqualTo(Money.ZERO);
        assertThat(Money.of(500).times(Quantity.of("0.001"))).isEqualTo(Money.of(1));
        assertThat(Money.of(3).times(Quantity.of(4))).isEqualTo(Money.of(12));
    }

    @Test
    void quantityNormalizesToScaleThree() {
        assertThat(Quantity.of("1").value().scale()).isEqualTo(3);
        assertThat(Quantity.of("0.0005")).isEqualTo(Quantity.of("0.001"));
        assertThat(Quantity.of("0.0004")).isEqualTo(Quantity.ZERO);
    }

    @Test
    void quantityWholeNumberCheck() {
        assertThat(Quantity.of(3).isWholeNumber()).isTrue();
        assertThat(Quantity.of("3.000").isWholeNumber()).isTrue();
        assertThat(Quantity.of("2.500").isWholeNumber()).isFalse();
    }

    @Test
    void quantityArithmetic() {
        assertThat(Quantity.of("1.250").plus(Quantity.of("0.750"))).isEqualTo(Quantity.of(2));
        assertThat(Quantity.of("1.250").minus(Quantity.of("1.250")).signum()).isZero();
        assertThat(Quantity.of(1)).isLessThan(Quantity.of(2));
    }

    @Test
    void roundingByDataType() {
        assertThat(Rounding.clp(new BigDecimal("6742.5"))).isEqualByComparingTo("6743").extracting(BigDecimal::scale)
                .isEqualTo(0);
        assertThat(Rounding.unitCost(new BigDecimal("1.23455"))).isEqualTo(new BigDecimal("1.2346"));
        assertThat(Rounding.costTotal(new BigDecimal("1.005"))).isEqualTo(new BigDecimal("1.01"));
        assertThat(Rounding.quantity(new BigDecimal("0.0005"))).isEqualTo(new BigDecimal("0.001"));
        assertThat(Rounding.percentage(new BigDecimal("19"))).isEqualTo(new BigDecimal("19.0000"));
    }
}
