package cl.minimarket.pos.cash.domain;

import cl.minimarket.pos.cash.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias de la fórmula oficial del esperado en caja ({@code MODELO_DATOS.md} B.8).
 */
class CashExpectedCalculatorTest {

    @Test
    void calculatesCashExpectedWithOpeningMovementsAndContributors() {
        CashExpectedCalculator.ExpectedTotals totals = CashExpectedCalculator.calculate(
                new BigDecimal("50000"),
                new BigDecimal("20000"),
                new BigDecimal("15000"),
                List.of(
                        Map.of(
                                PaymentMethod.CASH, new BigDecimal("85000"),
                                PaymentMethod.DEBIT, new BigDecimal("42000")
                        ),
                        Map.of(
                                PaymentMethod.CASH, new BigDecimal("10000"),
                                PaymentMethod.TRANSFER, new BigDecimal("5000")
                        )
                )
        );

        // 50.000 + 20.000 - 15.000 + (85.000 + 10.000) = 150.000
        assertThat(totals.expectedCash()).isEqualByComparingTo("150000");
        assertThat(totals.expectedByMethod().get(PaymentMethod.CASH)).isEqualByComparingTo("150000");
        assertThat(totals.expectedByMethod().get(PaymentMethod.DEBIT)).isEqualByComparingTo("42000");
        assertThat(totals.expectedByMethod().get(PaymentMethod.TRANSFER)).isEqualByComparingTo("5000");
        assertThat(totals.expectedByMethod().get(PaymentMethod.CREDIT)).isEqualByComparingTo("0");
        assertThat(totals.expectedByMethod().get(PaymentMethod.CUSTOMER_CREDIT)).isEqualByComparingTo("0");
    }

    @Test
    void handlesNegativeContributorNetWhenRefundsExceedCashSales() {
        CashExpectedCalculator.ExpectedTotals totals = CashExpectedCalculator.calculate(
                new BigDecimal("50000"),
                BigDecimal.ZERO,
                new BigDecimal("5000"),
                List.of(Map.of(PaymentMethod.CASH, new BigDecimal("-12000")))
        );

        // 50.000 + 0 - 5.000 - 12.000 = 33.000
        assertThat(totals.expectedCash()).isEqualByComparingTo("33000");
    }

    @Test
    void handlesNullAndEmptyInputsSafelyAndNormalizesClpScale() {
        CashExpectedCalculator.ExpectedTotals totals = CashExpectedCalculator.calculate(
                new BigDecimal("10000.6"),
                null,
                null,
                List.of()
        );

        assertThat(totals.expectedCash()).isEqualByComparingTo("10001");
        assertThat(totals.expectedCash().scale()).isZero();
        assertThat(totals.expectedByMethod()).containsKeys(PaymentMethod.values());
    }
}
