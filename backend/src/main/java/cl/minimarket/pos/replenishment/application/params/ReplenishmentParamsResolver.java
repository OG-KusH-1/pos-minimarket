package cl.minimarket.pos.replenishment.application.params;

import cl.minimarket.pos.replenishment.domain.ReplenishmentParameters;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Resolución jerárquica de los parámetros efectivos de reabastecimiento ({@code PLAN.md} C.2).
 *
 * <p>Los niveles van del más específico al más general:
 * <ol>
 *   <li>PRODUCT (primero con local, si la fila lo declara, y después sin local),</li>
 *   <li>CATEGORY de la cadena del producto (subcategoría y después categoría padre; primero
 *       con local y después sin local),</li>
 *   <li>STORE,</li>
 *   <li>GLOBAL.</li>
 * </ol>
 *
 * <p>La herencia es <b>por columna</b> (B.11: "una columna NULL = hereda del alcance superior"):
 * se ordenan las filas por especificidad y cada parámetro toma el primer valor no nulo que
 * encuentra. {@code enabled} sin ningún valor explícito resuelve {@code true} (default de C.2).
 *
 * <p>Es una función pura sobre las filas ya cargadas: no toca base de datos ni relojes, de modo
 * que se puede probar unitariamente.
 */
public final class ReplenishmentParamsResolver {

    private ReplenishmentParamsResolver() {
    }

    /**
     * Resuelve los parámetros efectivos de un producto en un local.
     *
     * @param productId     producto evaluado, o {@code null} para resolver el default del local
     *                      (ignora las filas de alcance PRODUCT y CATEGORY; lo usa el snapshot de
     *                      parámetros de la corrida)
     * @param categoryChain categorías del producto de la más específica a la más general
     *                      ({@code ProductCatalogApi.categoryChain}); vacía o {@code null} si el
     *                      producto no tiene categoría
     * @param storeId       local de la corrida
     * @param rows          todas las filas de {@code replenishment_params}
     * @return parámetros efectivos (nunca nulos; con los defaults de C.2 donde no hay configuración)
     */
    public static ReplenishmentParameters resolve(Long productId,
                                                  List<Long> categoryChain,
                                                  Long storeId,
                                                  List<ReplenishmentParam> rows) {
        Objects.requireNonNull(storeId, "storeId");
        List<Long> chain = categoryChain == null ? List.of() : categoryChain;

        // Especificidad: product(0) < category(1) < store(2) < global(3). Dentro de product y
        // category: primero la fila con local propio y después la sin local; en category, la
        // subcategoría antes que la padre (posición en la cadena).
        Comparator<ReplenishmentParam> specificity = Comparator
                .comparingInt((ReplenishmentParam row) -> rank(row, productId, chain, storeId))
                .thenComparingInt(row -> row.getStoreId() == null ? 1 : 0)
                .thenComparingInt(row -> row.getCategoryId() == null
                        ? 0
                        : Math.max(0, chain.indexOf(row.getCategoryId())));

        List<ReplenishmentParam> ordered = new ArrayList<>(rows);
        ordered.removeIf(row -> rank(row, productId, chain, storeId) == Integer.MAX_VALUE);
        ordered.sort(specificity);

        Integer windowDays = first(ordered, ReplenishmentParam::getWindowDays);
        Integer minHistoryDays = first(ordered, ReplenishmentParam::getMinHistoryDays);
        BigDecimal serviceLevelZ = first(ordered, ReplenishmentParam::getServiceLevelZ);
        BigDecimal safetyMinDays = first(ordered, ReplenishmentParam::getSafetyMinDays);
        Integer reviewPeriodDays = first(ordered, ReplenishmentParam::getReviewPeriodDays);
        BigDecimal outlierK = first(ordered, ReplenishmentParam::getOutlierK);
        BigDecimal shelfLifeFactor = first(ordered, ReplenishmentParam::getShelfLifeFactor);
        Boolean enabled = first(ordered, ReplenishmentParam::getEnabled);

        ReplenishmentParameters defaults = ReplenishmentParameters.defaults(
                enabled == null || enabled);
        return new ReplenishmentParameters(
                windowDays == null ? defaults.windowDays() : windowDays,
                minHistoryDays == null ? defaults.minHistoryDays() : minHistoryDays,
                serviceLevelZ == null ? defaults.serviceLevelZ() : serviceLevelZ,
                safetyMinDays == null ? defaults.safetyMinDays() : safetyMinDays,
                reviewPeriodDays,
                outlierK == null ? defaults.outlierK() : outlierK,
                shelfLifeFactor == null ? defaults.shelfLifeFactor() : shelfLifeFactor,
                enabled == null || enabled);
    }

    /** Rango de especificidad de una fila respecto del producto/local evaluado. */
    private static int rank(ReplenishmentParam row, Long productId, List<Long> chain, Long storeId) {
        if (row.getProductId() != null) {
            return Objects.equals(row.getProductId(), productId) ? 0 : Integer.MAX_VALUE;
        }
        if (row.getCategoryId() != null) {
            return chain.contains(row.getCategoryId()) ? 1 : Integer.MAX_VALUE;
        }
        if (row.getStoreId() != null) {
            return row.getStoreId().equals(storeId) ? 2 : Integer.MAX_VALUE;
        }
        return 3;
    }

    /** Primer valor no nulo de una columna entre las filas ya ordenadas por especificidad. */
    private static <T> T first(List<ReplenishmentParam> ordered,
                               java.util.function.Function<ReplenishmentParam, T> getter) {
        for (ReplenishmentParam row : ordered) {
            T value = getter.apply(row);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
