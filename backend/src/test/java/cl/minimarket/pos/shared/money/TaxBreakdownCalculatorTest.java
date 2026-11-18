package cl.minimarket.pos.shared.money;

import cl.minimarket.pos.shared.money.TaxBreakdownCalculator.Breakdown;
import cl.minimarket.pos.shared.money.TaxBreakdownCalculator.TaxAmount;
import cl.minimarket.pos.shared.money.TaxBreakdownCalculator.TaxRate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TaxBreakdownCalculatorTest {

    private static final TaxRate IVA = new TaxRate(1, new BigDecimal("19.0000"));
    private static final TaxRate ILA = new TaxRate(2, new BigDecimal("31.5000"));
    private static final TaxRate IABA = new TaxRate(3, new BigDecimal("18.0000"));

    @Test
    void ivaOnly() {
        Breakdown result = TaxBreakdownCalculator.calculate(Money.of(1190), List.of(IVA), false);

        assertThat(result.net()).isEqualTo(Money.of(1000));
        assertThat(result.taxTotal()).isEqualTo(Money.of(190));
        assertThat(result.exempt()).isEqualTo(Money.ZERO);
        assertThat(result.taxes()).containsExactly(new TaxAmount(1, Money.of(190)));
    }

    /** HU-CAT-07 (1): bebida con IVA 19 % e IABA 18 %, cotizar $1.370: neto $1.000 y desglose 370. */
    @Test
    void ivaPlusIaba() {
        Breakdown result = TaxBreakdownCalculator.calculate(Money.of(1370), List.of(IVA, IABA), false);

        assertThat(result.net()).isEqualTo(Money.of(1000));
        assertThat(result.taxTotal()).isEqualTo(Money.of(370));
        assertThat(result.taxes()).containsExactly(
                new TaxAmount(1, Money.of(190)), new TaxAmount(3, Money.of(180)));
    }

    /** IVA 19 % + ILA 31,5 %: R = 0,505; net = round(1315 / 1,505) = 874; el último (ILA) absorbe la diferencia. */
    @Test
    void ivaPlusIla() {
        Breakdown result = TaxBreakdownCalculator.calculate(Money.of(1315), List.of(IVA, ILA), false);

        assertThat(result.net()).isEqualTo(Money.of(874));
        assertThat(result.taxTotal()).isEqualTo(Money.of(441));
        assertThat(result.taxes()).containsExactly(
                new TaxAmount(1, Money.of(166)), new TaxAmount(2, Money.of(275)));
    }

    @Test
    void exemptWithoutOtherTaxes() {
        Breakdown result = TaxBreakdownCalculator.calculate(Money.of(1000), List.of(), true);

        assertThat(result.exempt()).isEqualTo(Money.of(1000));
        assertThat(result.net()).isEqualTo(Money.ZERO);
        assertThat(result.taxTotal()).isEqualTo(Money.ZERO);
        assertThat(result.taxes()).isEmpty();
    }

    /** Exento con ILA: se desglosa solo el ILA (R = 0,315) y line_exempt = 0. */
    @Test
    void exemptWithAdditionalTax() {
        Breakdown result = TaxBreakdownCalculator.calculate(Money.of(1315), List.of(ILA), true);

        assertThat(result.net()).isEqualTo(Money.of(1000));
        assertThat(result.taxTotal()).isEqualTo(Money.of(315));
        assertThat(result.exempt()).isEqualTo(Money.ZERO);
        assertThat(result.taxes()).containsExactly(new TaxAmount(2, Money.of(315)));
    }

    @Test
    void productWithoutAnyTaxAndNotExemptKeepsGrossAsNet() {
        Breakdown result = TaxBreakdownCalculator.calculate(Money.of(500), List.of(), false);

        assertThat(result.net()).isEqualTo(Money.of(500));
        assertThat(result.exempt()).isEqualTo(Money.ZERO);
    }

    @Test
    void lastTaxByIdAbsorbsTheDifferenceRegardlessOfInputOrder() {
        Breakdown result = TaxBreakdownCalculator.calculate(Money.of(1315), List.of(ILA, IVA), false);

        assertThat(result.taxes()).extracting(TaxAmount::taxId).containsExactly(1L, 2L);
        assertThat(result.taxes().get(1).amount()).isEqualTo(Money.of(275));
    }

    /** Para cualquier bruto: net + impuestos + exento = bruto y Σ tax_i = tax_total. */
    @Test
    void breakdownAlwaysAddsUpToGross() {
        for (long gross = 0; gross <= 5000; gross++) {
            Breakdown result = TaxBreakdownCalculator.calculate(Money.of(gross), List.of(IVA, IABA, ILA), false);

            assertThat(result.net().plus(result.taxTotal()).plus(result.exempt())).isEqualTo(Money.of(gross));
            assertThat(Money.sum(result.taxes().stream().map(TaxAmount::amount).toList()))
                    .isEqualTo(result.taxTotal());
        }
    }

    @Test
    void negativeRateIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TaxRate(1, new BigDecimal("-1")));
    }
}
