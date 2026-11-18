package cl.minimarket.pos.inventory.domain;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de {@link FefoAllocator}.
 */
class FefoAllocatorTest {

    @Test
    void allocatesAcrossMultipleLotsInProvidedFefoOrderAndIgnoresDepleted() {
        List<FefoAllocator.AvailableLot> lots = List.of(
                new FefoAllocator.AvailableLot(1L, "L-DEP", LocalDate.of(2027, 1, 1), BigDecimal.ZERO),
                new FefoAllocator.AvailableLot(2L, "L-1", LocalDate.of(2027, 2, 1), new BigDecimal("3.000")),
                new FefoAllocator.AvailableLot(3L, "L-2", LocalDate.of(2027, 3, 1), new BigDecimal("4.000")),
                new FefoAllocator.AvailableLot(4L, "SIN-LOTE", null, new BigDecimal("10.000"))
        );

        List<FefoAllocator.LotDeduction> result =
                FefoAllocator.allocate(50L, new BigDecimal("5.500"), lots);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).lotId()).isEqualTo(2L);
        assertThat(result.get(0).allocatedQuantity()).isEqualByComparingTo("3.000");
        assertThat(result.get(0).balanceAfter()).isEqualByComparingTo("0.000");

        assertThat(result.get(1).lotId()).isEqualTo(3L);
        assertThat(result.get(1).allocatedQuantity()).isEqualByComparingTo("2.500");
        assertThat(result.get(1).balanceAfter()).isEqualByComparingTo("1.500");
    }

    @Test
    void throwsStockInsufficientWhenLotSumIsSmallerThanRequested() {
        List<FefoAllocator.AvailableLot> lots = List.of(
                new FefoAllocator.AvailableLot(10L, "L-1", LocalDate.of(2027, 2, 1), new BigDecimal("2.000")),
                new FefoAllocator.AvailableLot(11L, "SIN-LOTE", null, new BigDecimal("1.000"))
        );

        assertThatThrownBy(() -> FefoAllocator.allocate(50L, new BigDecimal("3.500"), lots))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STOCK_INSUFFICIENT);
    }

    @Test
    void suggestReturnsPartialAllocationsAndShortageWithoutThrowingWhenInsufficient() {
        List<FefoAllocator.AvailableLot> lots = List.of(
                new FefoAllocator.AvailableLot(10L, "L-1", LocalDate.of(2027, 2, 1), new BigDecimal("2.000")),
                new FefoAllocator.AvailableLot(11L, "SIN-LOTE", null, new BigDecimal("1.000"))
        );

        FefoAllocator.FefoAllocationResult partial = FefoAllocator.suggest(new BigDecimal("4.500"), lots);
        assertThat(partial.deductions()).hasSize(2);
        assertThat(partial.deductions().get(0).lotId()).isEqualTo(10L);
        assertThat(partial.deductions().get(0).allocatedQuantity()).isEqualByComparingTo("2.000");
        assertThat(partial.deductions().get(1).lotId()).isEqualTo(11L);
        assertThat(partial.deductions().get(1).allocatedQuantity()).isEqualByComparingTo("1.000");
        assertThat(partial.shortage()).isEqualByComparingTo("1.500");

        FefoAllocator.FefoAllocationResult empty = FefoAllocator.suggest(new BigDecimal("2.000"), List.of());
        assertThat(empty.deductions()).isEmpty();
        assertThat(empty.shortage()).isEqualByComparingTo("2.000");
    }
}
