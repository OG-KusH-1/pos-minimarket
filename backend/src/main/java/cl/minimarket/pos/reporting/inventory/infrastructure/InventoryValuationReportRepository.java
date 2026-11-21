package cl.minimarket.pos.reporting.inventory.infrastructure;

import cl.minimarket.pos.reporting.inventory.InventoryValuationGroupBy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Repositorio JDBC de solo lectura para la valorización actual del inventario ({@code ADR-11},
 * {@code HU-REP-07}).
 *
 * <p>Calcula {@code Σ stock.quantity × products.avg_cost} sobre las existencias positivas actuales
 * de los locales solicitados.
 */
@Repository
public class InventoryValuationReportRepository {

    private final JdbcClient jdbc;

    public InventoryValuationReportRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Proyección cruda agrupada de cantidad y valorización. */
    public record ValuationAggregateRow(
            String key,
            String label,
            BigDecimal qty,
            BigDecimal valuation
    ) {
    }

    /**
     * Consulta las existencias positivas actuales y su valorización agrupadas según {@code groupBy}.
     *
     * @param storeIds lista no vacía de identificadores de local
     * @param groupBy  criterio de agrupación
     * @return filas ordenadas por valorización descendente
     */
    public List<ValuationAggregateRow> findValuationRows(
            List<Long> storeIds,
            InventoryValuationGroupBy groupBy) {
        Objects.requireNonNull(storeIds, "storeIds");
        Objects.requireNonNull(groupBy, "groupBy");
        if (storeIds.isEmpty()) {
            return List.of();
        }

        String sql = switch (groupBy) {
            case CATEGORY -> """
                    SELECT CAST(c.id AS text) AS row_key,
                           c.name AS row_label,
                           COALESCE(SUM(s.quantity), 0) AS qty,
                           COALESCE(ROUND(SUM(s.quantity * p.avg_cost), 2), 0) AS valuation
                    FROM stock s
                    JOIN products p ON p.id = s.product_id
                    JOIN categories c ON c.id = p.category_id
                    WHERE s.store_id IN (:storeIds)
                      AND s.quantity > 0
                      AND p.sale_type <> 'PACK'
                    GROUP BY c.id, c.name
                    ORDER BY valuation DESC, row_label ASC, row_key ASC
                    """;
            case STORE -> """
                    SELECT CAST(st.id AS text) AS row_key,
                           st.name AS row_label,
                           COALESCE(SUM(s.quantity), 0) AS qty,
                           COALESCE(ROUND(SUM(s.quantity * p.avg_cost), 2), 0) AS valuation
                    FROM stock s
                    JOIN products p ON p.id = s.product_id
                    JOIN stores st ON st.id = s.store_id
                    WHERE s.store_id IN (:storeIds)
                      AND s.quantity > 0
                      AND p.sale_type <> 'PACK'
                    GROUP BY st.id, st.name
                    ORDER BY valuation DESC, row_label ASC, row_key ASC
                    """;
            case PRODUCT -> """
                    SELECT CAST(p.id AS text) AS row_key,
                           p.name AS row_label,
                           COALESCE(SUM(s.quantity), 0) AS qty,
                           COALESCE(ROUND(SUM(s.quantity * p.avg_cost), 2), 0) AS valuation
                    FROM stock s
                    JOIN products p ON p.id = s.product_id
                    WHERE s.store_id IN (:storeIds)
                      AND s.quantity > 0
                      AND p.sale_type <> 'PACK'
                    GROUP BY p.id, p.name
                    ORDER BY valuation DESC, row_label ASC, row_key ASC
                    """;
        };

        return jdbc.sql(sql)
                .param("storeIds", storeIds)
                .query((rs, rowNum) -> new ValuationAggregateRow(
                        rs.getString("row_key"),
                        rs.getString("row_label"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("valuation")))
                .list();
    }
}
