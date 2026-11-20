package cl.minimarket.pos.replenishment.application.params;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias de la resolución jerárquica de parámetros ({@code PLAN.md} C.2): producto →
 * categoría (subcategoría y luego padre) → local → global, con herencia por columna (B.11).
 */
class ReplenishmentParamsResolverTest {

    private static final long STORE = 7L;
    private static final long PRODUCT = 42L;
    private static final long SUBCATEGORY = 300L;
    private static final long ROOT_CATEGORY = 200L;

    /** Fila GLOBAL sin ninguna columna configurada (todo default). */
    private ReplenishmentParam global() {
        return new ReplenishmentParam(ReplenishmentParamScope.GLOBAL, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void withoutRowsResolvesTheDefaults() {
        var resolved = ReplenishmentParamsResolver.resolve(PRODUCT, List.of(ROOT_CATEGORY), STORE, List.of());

        assertThat(resolved.windowDays()).isEqualTo(30);
        assertThat(resolved.minHistoryDays()).isEqualTo(7);
        assertThat(resolved.serviceLevelZ()).isEqualByComparingTo("1.65");
        assertThat(resolved.safetyMinDays()).isEqualByComparingTo("1");
        assertThat(resolved.reviewPeriodDays()).isNull();
        assertThat(resolved.outlierK()).isEqualByComparingTo("3");
        assertThat(resolved.shelfLifeFactor()).isEqualByComparingTo("0.7");
        assertThat(resolved.enabled()).isTrue();
    }

    @Test
    void eachLevelWinsOverTheUpperOnes() {
        ReplenishmentParam globalRow = global();
        globalRow.applyValues(21, null, null, new BigDecimal("2"), null, null, null, Boolean.TRUE);
        ReplenishmentParam storeRow = new ReplenishmentParam(ReplenishmentParamScope.STORE, STORE, null, null,
                null, null, null, null, 2, null, null, null);
        ReplenishmentParam rootRow = new ReplenishmentParam(ReplenishmentParamScope.CATEGORY, null, ROOT_CATEGORY, null,
                null, 5, null, null, null, null, null, null);
        ReplenishmentParam productRow = new ReplenishmentParam(ReplenishmentParamScope.PRODUCT, null, null, PRODUCT,
                14, null, new BigDecimal("2.1"), null, null, new BigDecimal("4"), new BigDecimal("0.5"), null);

        var resolved = ReplenishmentParamsResolver.resolve(
                PRODUCT, List.of(SUBCATEGORY, ROOT_CATEGORY), STORE,
                List.of(globalRow, storeRow, rootRow, productRow));

        // Producto gana con sus propias columnas.
        assertThat(resolved.windowDays()).isEqualTo(14);
        assertThat(resolved.serviceLevelZ()).isEqualByComparingTo("2.1");
        assertThat(resolved.outlierK()).isEqualByComparingTo("4");
        assertThat(resolved.shelfLifeFactor()).isEqualByComparingTo("0.5");
        // Lo que el producto no fija viene del nivel más específico que lo tenga.
        assertThat(resolved.minHistoryDays()).isEqualTo(5); // categoría padre
        assertThat(resolved.reviewPeriodDays()).isEqualTo(2); // local
        assertThat(resolved.safetyMinDays()).isEqualByComparingTo("2"); // global
    }

    @Test
    void subcategoryBeatsParentCategoryAndStoreScopedRowsBeatUnscoped() {
        ReplenishmentParam subRow = new ReplenishmentParam(ReplenishmentParamScope.CATEGORY, null, SUBCATEGORY, null,
                10, null, null, null, null, null, null, null);
        ReplenishmentParam rootRow = new ReplenishmentParam(ReplenishmentParamScope.CATEGORY, null, ROOT_CATEGORY, null,
                20, null, null, null, null, null, null, null);
        ReplenishmentParam rootUnscopedRow = new ReplenishmentParam(
                ReplenishmentParamScope.CATEGORY, null, ROOT_CATEGORY, null,
                25, null, null, null, null, null, null, null);

        var resolved = ReplenishmentParamsResolver.resolve(
                PRODUCT, List.of(SUBCATEGORY, ROOT_CATEGORY), STORE,
                List.of(rootUnscopedRow, rootRow, subRow));

        assertThat(resolved.windowDays()).isEqualTo(10);
    }

    @Test
    void productRowForTheStoreBeatsProductRowWithoutStore() {
        ReplenishmentParam productWithStore = new ReplenishmentParam(
                ReplenishmentParamScope.PRODUCT, STORE, null, PRODUCT, 12, null, null, null, null, null, null, null);
        ReplenishmentParam productWithoutStore = new ReplenishmentParam(
                ReplenishmentParamScope.PRODUCT, null, null, PRODUCT, 15, null, null, null, null, null, null, null);

        var resolved = ReplenishmentParamsResolver.resolve(
                PRODUCT, List.of(), STORE, List.of(productWithoutStore, productWithStore));

        assertThat(resolved.windowDays()).isEqualTo(12);
    }

    @Test
    void rowsOfOtherStoresAndOtherProductsAreIgnored() {
        ReplenishmentParam otherStore = new ReplenishmentParam(ReplenishmentParamScope.STORE, 99L, null, null,
                99, null, null, null, 9, null, null, null);
        ReplenishmentParam otherProduct = new ReplenishmentParam(ReplenishmentParamScope.PRODUCT, null, null, 43L,
                99, 99, new BigDecimal("3"), new BigDecimal("9"), 9, null, null, Boolean.FALSE);

        var resolved = ReplenishmentParamsResolver.resolve(
                PRODUCT, List.of(ROOT_CATEGORY), STORE, List.of(otherStore, otherProduct));

        assertThat(resolved.windowDays()).isEqualTo(30);
        assertThat(resolved.minHistoryDays()).isEqualTo(7);
        assertThat(resolved.enabled()).isTrue();
    }

    @Test
    void enabledFalseDisablesTheProductAtAnyLevel() {
        ReplenishmentParam globalRow = global();
        globalRow.applyValues(null, null, null, null, null, null, null, Boolean.TRUE);
        ReplenishmentParam productRow = new ReplenishmentParam(ReplenishmentParamScope.PRODUCT, null, null, PRODUCT,
                null, null, null, null, null, null, null, Boolean.FALSE);

        var resolved = ReplenishmentParamsResolver.resolve(
                PRODUCT, List.of(), STORE, List.of(globalRow, productRow));

        assertThat(resolved.enabled()).isFalse();
    }

    @Test
    void storeDefaultForSnapshotIgnoresProductAndCategoryRows() {
        ReplenishmentParam storeRow = new ReplenishmentParam(ReplenishmentParamScope.STORE, STORE, null, null,
                25, null, null, null, 3, null, null, null);
        ReplenishmentParam productRow = new ReplenishmentParam(ReplenishmentParamScope.PRODUCT, null, null, PRODUCT,
                10, null, null, null, null, null, null, null);

        var resolved = ReplenishmentParamsResolver.resolve(
                null, List.of(), STORE, List.of(storeRow, productRow));

        assertThat(resolved.windowDays()).isEqualTo(25);
        assertThat(resolved.reviewPeriodDays()).isEqualTo(3);
    }
}
