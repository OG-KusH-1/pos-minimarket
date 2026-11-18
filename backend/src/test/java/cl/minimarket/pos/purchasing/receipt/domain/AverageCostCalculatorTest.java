package cl.minimarket.pos.purchasing.receipt.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias de {@link AverageCostCalculator} ({@code PLAN.md} A.5, {@code HU-PRV-07}).
 */
class AverageCostCalculatorTest {

    @Test
    void weightedAverage_q10At500Plus30At600_returns575_0000() {
        BigDecimal result = AverageCostCalculator.calculate(
                new BigDecimal("10.000"),
                new BigDecimal("500.0000"),
                new BigDecimal("30.000"),
                BigDecimal.ZERO,
                new BigDecimal("600.0000"));

        assertThat(result).isEqualByComparingTo("575.0000");
        assertThat(result.scale()).isEqualTo(4);
    }

    @Test
    void whenCompanyQuantityIsZero_returnsInboundUnitCost() {
        BigDecimal result = AverageCostCalculator.calculate(
                BigDecimal.ZERO,
                new BigDecimal("500.0000"),
                new BigDecimal("15.000"),
                BigDecimal.ZERO,
                new BigDecimal("600.0000"));

        assertThat(result).isEqualByComparingTo("600.0000");
        assertThat(result.scale()).isEqualTo(4);
    }

    @Test
    void rejectedQuantity_doesNotEnterStockOrCostCalculation() {
        // 35 recibidas, 5 rechazadas => q_in = 30 a $600 sobre Q=10 a $500 => 575.0000
        BigDecimal result = AverageCostCalculator.calculate(
                new BigDecimal("10.000"),
                new BigDecimal("500.0000"),
                new BigDecimal("35.000"),
                new BigDecimal("5.000"),
                new BigDecimal("600.0000"));

        assertThat(result).isEqualByComparingTo("575.0000");
    }

    @Test
    void whenAllQuantityIsRejected_keepsCurrentAverageCost() {
        BigDecimal result = AverageCostCalculator.calculate(
                new BigDecimal("10.000"),
                new BigDecimal("500.0000"),
                new BigDecimal("10.000"),
                new BigDecimal("10.000"),
                new BigDecimal("999.0000"));

        assertThat(result).isEqualByComparingTo("500.0000");
    }

    @Test
    void roundsHalfUpToFourDecimals() {
        // (1 * 100 + 2 * 101) / 3 = 302 / 3 = 100.666666... -> 100.6667
        BigDecimal result = AverageCostCalculator.calculate(
                new BigDecimal("1.000"),
                new BigDecimal("100.0000"),
                new BigDecimal("2.000"),
                BigDecimal.ZERO,
                new BigDecimal("101.0000"));

        assertThat(result).isEqualByComparingTo("100.6667");
    }
}
