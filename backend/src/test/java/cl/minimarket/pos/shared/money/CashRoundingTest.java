package cl.minimarket.pos.shared.money;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CashRoundingTest {

    /** Terminaciones 0 a 9: d ≤ 5 resta d; d > 5 suma 10 − d. */
    @ParameterizedTest(name = "{0} -> ajuste {1}")
    @CsvSource({
            "1000, 0", "1001, -1", "1002, -2", "1003, -3", "1004, -4",
            "1005, -5", "1006, 4", "1007, 3", "1008, 2", "1009, 1"
    })
    void adjustsEveryEnding(long cashDue, long expectedAdjustment) {
        Money adjustment = CashRounding.adjustment(Money.of(cashDue), true);

        assertThat(adjustment).isEqualTo(Money.of(expectedAdjustment));
        assertThat(CashRounding.round(Money.of(cashDue), true).toLong() % 10).isZero();
    }

    /** HU-POS-08 (1): $1.995 en efectivo, se cobra $1.990 (ajuste -5). */
    @Test
    void huPos08RoundsDown() {
        assertThat(CashRounding.adjustment(Money.of(1995), true)).isEqualTo(Money.of(-5));
        assertThat(CashRounding.round(Money.of(1995), true)).isEqualTo(Money.of(1990));
    }

    /** HU-POS-08 (2): $1.996 en efectivo, se cobra $2.000 (ajuste +4). */
    @Test
    void huPos08RoundsUp() {
        assertThat(CashRounding.adjustment(Money.of(1996), true)).isEqualTo(Money.of(4));
        assertThat(CashRounding.round(Money.of(1996), true)).isEqualTo(Money.of(2000));
    }

    @Test
    void disabledSettingMeansZeroAdjustment() {
        assertThat(CashRounding.adjustment(Money.of(1996), false)).isEqualTo(Money.ZERO);
        assertThat(CashRounding.round(Money.of(1996), false)).isEqualTo(Money.of(1996));
    }

    @Test
    void noCashDueMeansZeroAdjustment() {
        assertThat(CashRounding.adjustment(Money.ZERO, true)).isEqualTo(Money.ZERO);
        assertThat(CashRounding.adjustment(Money.of(-7), true)).isEqualTo(Money.ZERO);
    }

    /** Pago mixto: solo la porción en efectivo se redondea (total 5.003, tarjeta 2.000, efectivo 3.003). */
    @Test
    void mixedPaymentRoundsOnlyTheCashPortion() {
        Money adjustment = CashRounding.adjustmentForMixedPayment(Money.of(5003), Money.of(2000), true);

        assertThat(adjustment).isEqualTo(Money.of(-3));
    }

    @Test
    void mixedPaymentFullyCoveredByCardHasNoAdjustment() {
        assertThat(CashRounding.adjustmentForMixedPayment(Money.of(5003), Money.of(5003), true))
                .isEqualTo(Money.ZERO);
    }

    @Test
    void adjustmentStaysWithinRangeForEveryEnding() {
        for (long value = 1; value <= 100; value++) {
            long adjustment = CashRounding.adjustment(Money.of(value), true).toLong();
            assertThat(adjustment).isBetween(-5L, 4L);
        }
    }

    @Test
    void settingKeyMatchesThePlan() {
        assertThat(CashRounding.SETTING_KEY).isEqualTo("pos.cash_rounding.enabled");
    }
}
