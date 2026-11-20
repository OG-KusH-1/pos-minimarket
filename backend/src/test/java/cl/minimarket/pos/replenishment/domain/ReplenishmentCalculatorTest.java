package cl.minimarket.pos.replenishment.domain;

import cl.minimarket.pos.purchasing.ProductSupplierApi.ProductSupplierCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Pruebas de los ejemplos C.7 y de todos los casos borde C.6 del algoritmo de reabastecimiento. */
class ReplenishmentCalculatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 12);

    @Test
    void example1_beverage_matchesDocumentExactly() {
        List<String> validQuantities = new ArrayList<>();
        for (int index = 0; index < 22; index++) {
            validQuantities.add("10");
        }
        for (int index = 0; index < 4; index++) {
            validQuantities.add("14.5");
        }
        validQuantities.add("1");
        validQuantities.add("1");

        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(30, 7, "1.65", "1", null))
                .withDemandHistory(windowHistory(30, validQuantities, 4, 17))
                .withSuppliers(supplier(10, 100, "50", "12", "2", 3, false))
                .withOnHand("25")
                .withInTransit("12")
                .withMaxQuantity("120")
                .build());

        assertThat(result.validDays()).isEqualTo(28);
        assertThat(result.censoredDays()).isEqualTo(2);
        assertDecimal(result.averageDailyDemand(), "10.0000");
        assertDecimal(result.demandStandardDeviation(), "3.0000");
        assertDecimal(result.safetyStock(), "10.0000");
        assertDecimal(result.reorderPoint(), "40.0000");
        assertDecimal(result.targetLevel(), "110.0000");
        assertDecimal(result.suggestedPurchaseUnits(), "6.000");
        assertDecimal(result.suggestedQuantity(), "72.000");
        assertThat(result.status()).isEqualTo(ReplenishmentStatus.SUGGESTED);
        assertThat(result.flags()).containsExactlyInAnyOrder(
                ReplenishmentFlag.CENSORED_DAYS,
                ReplenishmentFlag.CAPPED_BY_UPPER);
        assertThat(result.estimatedStockoutDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertDecimal(result.daysOfCover(), "2.50");
    }

    @Test
    void example2_weighableCheese_matchesDocumentExactly() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(3, 3, "1.65", "1", null))
                .withDailyQuantities("1.6", "2.4", "3.2")
                .withSuppliers(supplier(20, 200, "1000", "5", "1", 2, false, 1, 4))
                .withOnHand("5.250")
                .withInTransit("0")
                .withShelfLifeDays(8)
                .build());

        assertDecimal(result.averageDailyDemand(), "2.4000");
        assertDecimal(result.demandStandardDeviation(), "0.8000");
        assertThat(result.reviewPeriodDays()).isEqualTo(4);
        assertDecimal(result.safetyStock(), "2.4000");
        assertDecimal(result.reorderPoint(), "7.2000");
        assertDecimal(result.targetLevel(), "13.4400");
        assertDecimal(result.suggestedPurchaseUnits(), "1.000");
        assertDecimal(result.suggestedQuantity(), "5.000");
        assertDecimal(result.unitsPerPurchaseUnit(), "5.000");
        assertThat(result.status()).isEqualTo(ReplenishmentStatus.SUGGESTED);
        assertThat(result.flags()).containsExactlyInAnyOrder(
                ReplenishmentFlag.SHORT_SHELF_LIFE,
                ReplenishmentFlag.CAPPED_BY_UPPER);
        assertThat(result.estimatedStockoutDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertDecimal(result.daysOfCover(), "2.19");
    }

    @Test
    void example3_packConsolidatedBeer_matchesDocumentExactly() {
        List<String> validQuantities = List.of("6", "8", "7", "5", "6", "48", "7", "6", "8", "5", "7", "6");

        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(14, 7, "0", "2", null))
                .withDemandHistory(windowHistory(14, validQuantities, 2, 9))
                .withSuppliers(supplier(30, 300, "20", "24", "1", 1, false))
                .withOnHand("18")
                .withInTransit("0")
                .build());

        assertThat(result.validDays()).isEqualTo(12);
        assertThat(result.censoredDays()).isEqualTo(2);
        assertDecimal(result.averageDailyDemand(), "6.6437");
        assertDecimal(result.safetyStock(), "13.2874");
        assertDecimal(result.reorderPoint(), "19.9311");
        assertDecimal(result.targetLevel(), "66.4370");
        assertDecimal(result.suggestedPurchaseUnits(), "3.000");
        assertDecimal(result.suggestedQuantity(), "72.000");
        assertThat(result.status()).isEqualTo(ReplenishmentStatus.SUGGESTED);
        assertThat(result.flags()).containsExactlyInAnyOrder(
                ReplenishmentFlag.CENSORED_DAYS,
                ReplenishmentFlag.OUTLIERS_CAPPED);
        assertThat(result.flags()).doesNotContain(ReplenishmentFlag.SHORT_HISTORY);
        assertThat(result.estimatedStockoutDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertDecimal(result.daysOfCover(), "2.71");
    }

    @Test
    void newProduct_withMinimum_replenishesUpToMinimum() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(30, 7, "1.65", "1", 7))
                .withDailyQuantities("4", "6")
                .withSuppliers(supplier(40, 400, "10", "2", "1", 3, false))
                .withOnHand("2")
                .withMinQuantity("10")
                .build());

        assertDecimal(result.averageDailyDemand(), "0.0000");
        assertDecimal(result.demandStandardDeviation(), "0.0000");
        assertDecimal(result.targetLevel(), "10.0000");
        assertDecimal(result.suggestedPurchaseUnits(), "4.000");
        assertDecimal(result.suggestedQuantity(), "8.000");
        assertThat(result.status()).isEqualTo(ReplenishmentStatus.SUGGESTED);
        assertThat(result.flags()).containsExactlyInAnyOrder(
                ReplenishmentFlag.NEW_PRODUCT,
                ReplenishmentFlag.SHORT_HISTORY);
        assertThat(result.estimatedStockoutDate()).isNull();
        assertThat(result.daysOfCover()).isNull();
    }

    @Test
    void newProduct_withoutMinimum_returnsNoAction() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(30, 7, "1.65", "1", 7))
                .withDailyQuantities("4", "6")
                .withOnHand("2")
                .build());

        assertThat(result.status()).isEqualTo(ReplenishmentStatus.NO_ACTION);
        assertThat(result.flags()).containsExactlyInAnyOrder(
                ReplenishmentFlag.NEW_PRODUCT,
                ReplenishmentFlag.SHORT_HISTORY,
                ReplenishmentFlag.ZERO_DEMAND);
        assertDecimal(result.suggestedQuantity(), "0.000");
    }

    @Test
    void allStockoutDays_areCensoredAndTreatedAsNewProduct() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(7, 7, "1.65", "1", 7))
                .withDemandHistory(windowHistory(7, List.of(), 0, 1, 2, 3, 4, 5, 6))
                .build());

        assertThat(result.validDays()).isZero();
        assertThat(result.censoredDays()).isEqualTo(7);
        assertThat(result.status()).isEqualTo(ReplenishmentStatus.NO_ACTION);
        assertThat(result.flags()).containsExactlyInAnyOrder(
                ReplenishmentFlag.NEW_PRODUCT,
                ReplenishmentFlag.CENSORED_DAYS,
                ReplenishmentFlag.ZERO_DEMAND);
        assertThat(result.flags()).doesNotContain(ReplenishmentFlag.SHORT_HISTORY);
    }

    @Test
    void shortHistory_dividesByValidDaysInsteadOfWindow() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(10, 2, "0", "0", 7))
                .withDailyQuantities("4", "6")
                .withOnHand("20")
                .build());

        assertThat(result.validDays()).isEqualTo(2);
        assertDecimal(result.averageDailyDemand(), "5.0000");
        assertThat(result.status()).isEqualTo(ReplenishmentStatus.NO_ACTION);
        assertThat(result.flags()).containsExactly(ReplenishmentFlag.SHORT_HISTORY);
        assertThat(result.flags()).doesNotContain(ReplenishmentFlag.NEW_PRODUCT);
    }

    @Test
    void zeroMedianAbsoluteDeviation_doesNotCapExtremeValues() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(7, 7, "0", "0", 7))
                .withDailyQuantities("10", "10", "10", "10", "10", "1", "19")
                .withOnHand("100")
                .build());

        assertDecimal(result.averageDailyDemand(), "10.0000");
        assertThat(result.flags()).doesNotContain(ReplenishmentFlag.OUTLIERS_CAPPED);
    }

    @Test
    void packsInactiveAndDisabledProducts_areExcludedWithoutEvaluation() {
        ReplenishmentResult pack = ReplenishmentCalculator.calculate(new Fixture().withPack(true).build());
        ReplenishmentResult inactive = ReplenishmentCalculator.calculate(new Fixture().withActive(false).build());
        ReplenishmentResult disabled = ReplenishmentCalculator.calculate(new Fixture().withEnabled(false).build());

        for (ReplenishmentResult result : List.of(pack, inactive, disabled)) {
            assertThat(result.status()).isEqualTo(ReplenishmentStatus.EXCLUDED);
            assertThat(result.validDays()).isZero();
            assertThat(result.censoredDays()).isZero();
            assertDecimal(result.suggestedQuantity(), "0.000");
            assertThat(result.flags()).isEmpty();
        }
    }

    @Test
    void noSupplier_calculatesWithUnitDefaultsAndReturnsNoSupplier() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(7, 7, "0", "0", 1))
                .withDailyQuantities("2", "2", "2", "2", "2", "2", "2")
                .withSuppliers()
                .withOnHand("0")
                .build());

        assertThat(result.status()).isEqualTo(ReplenishmentStatus.NO_SUPPLIER);
        assertThat(result.supplierId()).isNull();
        assertThat(result.productSupplierId()).isNull();
        assertThat(result.unitCostNet()).isNull();
        assertDecimal(result.unitsPerPurchaseUnit(), "1.000");
        assertDecimal(result.suggestedPurchaseUnits(), "2.000");
        assertDecimal(result.suggestedQuantity(), "2.000");
        assertThat(result.flags()).containsExactly(ReplenishmentFlag.LEAD_TIME_ZERO);
    }

    @Test
    void zeroLeadTime_usesOnlyMinimumSafetyDaysAndAddsFlag() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(7, 7, "1.65", "1", 7))
                .withDailyQuantities("2", "2", "2", "2", "2", "2", "2")
                .withSuppliers(supplier(50, 500, "10", "1", "1", 0, false))
                .withOnHand("0")
                .build());

        assertDecimal(result.safetyStock(), "2.0000");
        assertDecimal(result.reorderPoint(), "2.0000");
        assertThat(result.flags()).containsExactly(ReplenishmentFlag.LEAD_TIME_ZERO);
        assertThat(result.status()).isEqualTo(ReplenishmentStatus.SUGGESTED);
    }

    @Test
    void zeroDemand_withoutMinimum_returnsNoActionAndFlag() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(7, 7, "1.65", "1", 7))
                .withDailyQuantities("0", "0", "0", "0", "0", "0", "0")
                .build());

        assertThat(result.status()).isEqualTo(ReplenishmentStatus.NO_ACTION);
        assertThat(result.flags()).containsExactly(ReplenishmentFlag.ZERO_DEMAND);
        assertThat(result.estimatedStockoutDate()).isNull();
        assertThat(result.daysOfCover()).isNull();
    }

    @Test
    void zeroDemand_belowMinimum_replenishesWithoutZeroDemandFlag() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(7, 7, "1.65", "1", 7))
                .withDailyQuantities("0", "0", "0", "0", "0", "0", "0")
                .withOnHand("2")
                .withMinQuantity("10")
                .build());

        assertThat(result.status()).isEqualTo(ReplenishmentStatus.SUGGESTED);
        assertDecimal(result.suggestedQuantity(), "8.000");
        assertThat(result.flags()).doesNotContain(ReplenishmentFlag.ZERO_DEMAND);
    }

    @Test
    void minimumOrderQuantity_raisesPurchaseUnitsAndAddsFlag() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(7, 7, "0", "0", 1))
                .withDailyQuantities("1", "1", "1", "1", "1", "1", "1")
                .withSuppliers(supplier(60, 600, "10", "1", "5", 1, false))
                .withOnHand("1")
                .build());

        assertDecimal(result.targetLevel(), "2.0000");
        assertDecimal(result.suggestedPurchaseUnits(), "5.000");
        assertDecimal(result.suggestedQuantity(), "5.000");
        assertThat(result.flags()).containsExactly(ReplenishmentFlag.MOQ_APPLIED);
    }

    @Test
    void inventoryAboveReorderPoint_returnsNoAction() {
        ReplenishmentResult result = ReplenishmentCalculator.calculate(new Fixture()
                .withParameters(parameters(7, 7, "0", "0", 7))
                .withDailyQuantities("1", "1", "1", "1", "1", "1", "1")
                .withOnHand("10")
                .build());

        assertThat(result.status()).isEqualTo(ReplenishmentStatus.NO_ACTION);
        assertDecimal(result.suggestedQuantity(), "0.000");
        assertDecimal(result.suggestedPurchaseUnits(), "0.000");
        assertThat(result.flags()).isEmpty();
    }

    @Test
    void supplierSelection_preferredWinsOverCostLeadTimeAndId() {
        ProductSupplierCandidate cheaper = supplier(1, 1, "1", "1", "1", 0, false);
        ProductSupplierCandidate preferred = supplier(99, 99, "99", "1", "1", 30, true);

        Optional<ProductSupplierCandidate> selected = ReplenishmentCalculator.selectSupplier(
                List.of(cheaper, preferred));

        assertThat(selected.orElseThrow()).isSameAs(preferred);
    }

    @Test
    void supplierSelection_effectiveUnitCostWinsBeforeLeadTimeAndId() {
        ProductSupplierCandidate expensive = supplier(1, 1, "4", "1", "1", 1, false);
        ProductSupplierCandidate cheaperPerBaseUnit = supplier(99, 99, "3", "100", "1", 20, false);

        Optional<ProductSupplierCandidate> selected = ReplenishmentCalculator.selectSupplier(
                List.of(expensive, cheaperPerBaseUnit));

        assertThat(selected.orElseThrow()).isSameAs(cheaperPerBaseUnit);
    }

    @Test
    void supplierSelection_leadTimeBreaksCostTie() {
        ProductSupplierCandidate slow = supplier(1, 1, "3", "1", "1", 5, false);
        ProductSupplierCandidate fast = supplier(2, 2, "3", "1", "1", 2, false);

        Optional<ProductSupplierCandidate> selected = ReplenishmentCalculator.selectSupplier(
                List.of(slow, fast));

        assertThat(selected.orElseThrow()).isSameAs(fast);
    }

    @Test
    void supplierSelection_idBreaksCostAndLeadTimeTie() {
        ProductSupplierCandidate higherId = supplier(20, 1, "3", "1", "1", 2, false);
        ProductSupplierCandidate lowerId = supplier(10, 2, "3", "1", "1", 2, false);

        Optional<ProductSupplierCandidate> selected = ReplenishmentCalculator.selectSupplier(
                List.of(higherId, lowerId));

        assertThat(selected.orElseThrow()).isSameAs(lowerId);
    }

    private static ReplenishmentParameters parameters(
            int windowDays,
            int minHistoryDays,
            String serviceLevelZ,
            String safetyMinDays,
            Integer reviewPeriodDays) {
        return new ReplenishmentParameters(
                windowDays,
                minHistoryDays,
                new BigDecimal(serviceLevelZ),
                new BigDecimal(safetyMinDays),
                reviewPeriodDays,
                new BigDecimal("3"),
                new BigDecimal("0.7"),
                true);
    }

    private static ProductSupplierCandidate supplier(
            long id,
            long supplierId,
            String unitCostNet,
            String unitsPerPurchaseUnit,
            String minimumOrderQuantity,
            int leadTimeDays,
            boolean preferred,
            Integer... deliveryWeekdays) {
        BigDecimal units = new BigDecimal(unitsPerPurchaseUnit);
        BigDecimal unitCost = new BigDecimal(unitCostNet);
        return new ProductSupplierCandidate(
                id,
                1L,
                supplierId,
                "Proveedor " + supplierId,
                "Caja",
                units,
                unitCost.multiply(units),
                unitCost,
                new BigDecimal(minimumOrderQuantity),
                leadTimeDays,
                List.of(deliveryWeekdays),
                preferred);
    }

    /** Crea observaciones consecutivas que terminan el día anterior al corte. */
    private static List<DemandDay> history(String... quantities) {
        List<DemandDay> result = new ArrayList<>();
        for (int index = 0; index < quantities.length; index++) {
            result.add(new DemandDay(
                    AS_OF.minusDays(quantities.length - index),
                    new BigDecimal(quantities[index]),
                    false));
        }
        return result;
    }

    /**
     * Crea una ventana completa. Las posiciones censuradas reciben quiebre y no consumen un valor
     * de la lista de cantidades válidas, lo que permite reproducir N y Cd por separado.
     */
    private static List<DemandDay> windowHistory(
            int windowDays,
            List<String> validQuantities,
            int... stockoutPositions) {
        Set<Integer> stockouts = new HashSet<>(Arrays.stream(stockoutPositions).boxed().toList());
        List<DemandDay> result = new ArrayList<>();
        int validIndex = 0;
        for (int index = 0; index < windowDays; index++) {
            boolean stockout = stockouts.contains(index);
            BigDecimal quantity = stockout
                    ? BigDecimal.ZERO
                    : new BigDecimal(validQuantities.get(validIndex++));
            result.add(new DemandDay(AS_OF.minusDays(windowDays - index), quantity, stockout));
        }
        assertThat(validIndex).isEqualTo(validQuantities.size());
        return result;
    }

    /** Verifica valor y escala: los ejemplos fijan también los cuatro o tres decimales. */
    private static void assertDecimal(BigDecimal actual, String expected) {
        assertThat(actual).isEqualTo(new BigDecimal(expected));
    }

    /** Fixture mutable solo de prueba; cada build produce una entrada inmutable del dominio. */
    private static final class Fixture {
        private boolean active = true;
        private boolean pack;
        private ReplenishmentParameters parameters = parameters(7, 7, "0", "0", 1);
        private List<DemandDay> demandHistory = history("1", "1", "1", "1", "1", "1", "1");
        private List<ProductSupplierCandidate> suppliers = List.of(
                supplier(1, 1, "10", "1", "1", 1, false));
        private BigDecimal onHand = BigDecimal.ZERO;
        private BigDecimal inTransit = BigDecimal.ZERO;
        private BigDecimal minQuantity;
        private BigDecimal maxQuantity;
        private Integer shelfLifeDays;

        private Fixture withActive(boolean value) {
            active = value;
            return this;
        }

        private Fixture withPack(boolean value) {
            pack = value;
            return this;
        }

        private Fixture withEnabled(boolean value) {
            parameters = new ReplenishmentParameters(
                    parameters.windowDays(),
                    parameters.minHistoryDays(),
                    parameters.serviceLevelZ(),
                    parameters.safetyMinDays(),
                    parameters.reviewPeriodDays(),
                    parameters.outlierK(),
                    parameters.shelfLifeFactor(),
                    value);
            return this;
        }

        private Fixture withParameters(ReplenishmentParameters value) {
            parameters = value;
            return this;
        }

        private Fixture withDemandHistory(List<DemandDay> value) {
            demandHistory = value;
            return this;
        }

        private Fixture withDailyQuantities(String... values) {
            demandHistory = history(values);
            return this;
        }

        private Fixture withSuppliers(ProductSupplierCandidate... values) {
            suppliers = List.of(values);
            return this;
        }

        private Fixture withOnHand(String value) {
            onHand = new BigDecimal(value);
            return this;
        }

        private Fixture withInTransit(String value) {
            inTransit = new BigDecimal(value);
            return this;
        }

        private Fixture withMinQuantity(String value) {
            minQuantity = new BigDecimal(value);
            return this;
        }

        private Fixture withMaxQuantity(String value) {
            maxQuantity = new BigDecimal(value);
            return this;
        }

        private Fixture withShelfLifeDays(Integer value) {
            shelfLifeDays = value;
            return this;
        }

        private ReplenishmentInput build() {
            return new ReplenishmentInput(
                    1L,
                    active,
                    pack,
                    AS_OF.minusYears(1),
                    AS_OF,
                    demandHistory,
                    parameters,
                    suppliers,
                    onHand,
                    inTransit,
                    minQuantity,
                    maxQuantity,
                    shelfLifeDays);
        }
    }
}
