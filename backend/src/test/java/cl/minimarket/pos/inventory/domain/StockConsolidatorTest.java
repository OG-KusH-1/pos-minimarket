package cl.minimarket.pos.inventory.domain;

import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de {@link StockConsolidator}: consolidación por producto y lote, orden
 * determinista de {@code product_id} (RN-03) y validaciones de entrada.
 */
class StockConsolidatorTest {

    @Test
    void consolidatesRepeatedProductsAndSortsByProductIdAscending() {
        StockCommand cmd = new StockCommand(
                1L,
                StockMovementType.SALE,
                StockSourceType.SALE,
                100L,
                10L,
                "Venta POS",
                List.of(
                        StockLine.of(30L, new BigDecimal("2"), null, 1L),
                        StockLine.of(10L, new BigDecimal("1"), null, 2L),
                        StockLine.of(30L, new BigDecimal("3"), null, 3L),
                        StockLine.of(20L, new BigDecimal("4.500"), null, 4L)
                ));

        List<ConsolidatedStockLine> result = StockConsolidator.consolidate(cmd);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).productId()).isEqualTo(10L);
        assertThat(result.get(0).quantity()).isEqualByComparingTo("1.000");
        assertThat(result.get(0).sourceLineId()).isEqualTo(2L);

        assertThat(result.get(1).productId()).isEqualTo(20L);
        assertThat(result.get(1).quantity()).isEqualByComparingTo("4.500");

        assertThat(result.get(2).productId()).isEqualTo(30L);
        assertThat(result.get(2).quantity()).isEqualByComparingTo("5.000");
        // Líneas consolidadas con distinto sourceLineId dejan sourceLineId en null.
        assertThat(result.get(2).sourceLineId()).isNull();
    }

    @Test
    void consolidatesSameLotAndKeepsDistinctLotsOrdered() {
        LocalDate march = LocalDate.of(2027, 3, 1);
        LocalDate april = LocalDate.of(2027, 4, 1);
        StockCommand cmd = new StockCommand(
                1L,
                StockMovementType.PURCHASE_RECEIPT,
                StockSourceType.GOODS_RECEIPT,
                100L,
                10L,
                null,
                List.of(
                        new StockLine(10L, new BigDecimal("4"), new BigDecimal("500"), 1L,
                                null, "  LOTE-B  ", april),
                        new StockLine(10L, new BigDecimal("2"), new BigDecimal("400"), 2L,
                                null, "LOTE-A", march),
                        new StockLine(10L, new BigDecimal("3"), new BigDecimal("550"), 3L,
                                null, "LOTE-A", march),
                        new StockLine(10L, new BigDecimal("1"), new BigDecimal("600"), 4L,
                                99L, null, null)
                ));

        List<ConsolidatedStockLine> result = StockConsolidator.consolidate(cmd);

        assertThat(result).hasSize(1);
        ConsolidatedStockLine productLine = result.getFirst();
        assertThat(productLine.quantity()).isEqualByComparingTo("10.000");
        assertThat(productLine.hasLotData()).isTrue();
        assertThat(productLine.lotLines()).hasSize(3);

        // Orden determinista: lotId explícito primero, luego (lotNumber, expiryDate) ascendente.
        assertThat(productLine.lotLines().get(0).lotId()).isEqualTo(99L);
        assertThat(productLine.lotLines().get(0).quantity()).isEqualByComparingTo("1.000");

        assertThat(productLine.lotLines().get(1).lotNumber()).isEqualTo("LOTE-A");
        assertThat(productLine.lotLines().get(1).expiryDate()).isEqualTo(march);
        assertThat(productLine.lotLines().get(1).quantity()).isEqualByComparingTo("5.000");
        // Costo promedio ponderado: (2*400 + 3*550) / 5 = 490.0000
        assertThat(productLine.lotLines().get(1).unitCost()).isEqualByComparingTo("490.0000");

        assertThat(productLine.lotLines().get(2).lotNumber()).isEqualTo("LOTE-B");
        assertThat(productLine.lotLines().get(2).expiryDate()).isEqualTo(april);
        assertThat(productLine.lotLines().get(2).quantity()).isEqualByComparingTo("4.000");
    }

    @Test
    void rejectsEmptyLines() {
        StockCommand cmd = new StockCommand(
                1L, StockMovementType.SALE, StockSourceType.SALE, 100L, 10L, null, List.of());

        assertThatThrownBy(() -> StockConsolidator.consolidate(cmd))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        StockCommand cmd = new StockCommand(
                1L,
                StockMovementType.SALE,
                StockSourceType.SALE,
                100L,
                10L,
                null,
                List.of(StockLine.of(10L, BigDecimal.ZERO)));

        assertThatThrownBy(() -> StockConsolidator.consolidate(cmd))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsNegativeUnitCost() {
        StockCommand cmd = new StockCommand(
                1L,
                StockMovementType.PURCHASE_RECEIPT,
                StockSourceType.GOODS_RECEIPT,
                100L,
                10L,
                null,
                List.of(StockLine.of(10L, BigDecimal.ONE, new BigDecimal("-1.0000"), null)));

        assertThatThrownBy(() -> StockConsolidator.consolidate(cmd))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsExpiryDateWithoutLotTarget() {
        StockCommand cmd = new StockCommand(
                1L,
                StockMovementType.PURCHASE_RECEIPT,
                StockSourceType.GOODS_RECEIPT,
                100L,
                10L,
                null,
                List.of(new StockLine(10L, BigDecimal.ONE, new BigDecimal("500"), null,
                        null, "   ", LocalDate.of(2027, 1, 1))));

        assertThatThrownBy(() -> StockConsolidator.consolidate(cmd))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rejectsTooLongLotNumber() {
        String tooLong = "L".repeat(41);
        StockCommand cmd = new StockCommand(
                1L,
                StockMovementType.PURCHASE_RECEIPT,
                StockSourceType.GOODS_RECEIPT,
                100L,
                10L,
                null,
                List.of(new StockLine(10L, BigDecimal.ONE, new BigDecimal("500"), null,
                        null, tooLong, LocalDate.of(2027, 1, 1))));

        assertThatThrownBy(() -> StockConsolidator.consolidate(cmd))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
