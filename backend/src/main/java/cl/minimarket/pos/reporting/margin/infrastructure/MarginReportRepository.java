package cl.minimarket.pos.reporting.margin.infrastructure;

import cl.minimarket.pos.reporting.margin.MarginGroupBy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Repositorio JDBC de solo lectura para el reporte de margen y utilidad ({@code ADR-11}).
 *
 * <p>Lee directamente desde {@code sale_lines} y {@code sale_return_lines} para reflejar ventas y
 * devoluciones en tiempo real, utilizando el costo snapshot ({@code line_cost}) almacenado en cada
 * línea de venta.
 */
@Repository
public class MarginReportRepository {

    private static final String BASE_CTE = """
            WITH line_returns AS (
                SELECT srl.sale_line_id,
                       SUM(srl.quantity) AS returned_qty,
                       SUM(srl.refund_amount - srl.refund_tax) AS returned_net
                FROM sale_return_lines srl
                JOIN sale_lines sl ON sl.id = srl.sale_line_id
                JOIN sales s ON s.id = sl.sale_id
                WHERE sl.store_id IN (:storeIds)
                  AND sl.business_date BETWEEN :from AND :to
                  AND s.status <> 'VOIDED'
                GROUP BY srl.sale_line_id
            ),
            net_lines AS (
                SELECT sl.business_date,
                       sl.product_id,
                       p.name AS product_name,
                       p.category_id,
                       c.name AS category_name,
                       p.brand_id,
                       b.name AS brand_name,
                       (sl.quantity - COALESCE(lr.returned_qty, 0)) AS net_qty,
                       ((sl.line_net + sl.line_exempt) - COALESCE(lr.returned_net, 0)) AS net_sales,
                       CASE
                           WHEN COALESCE(lr.returned_qty, 0) = 0 THEN sl.line_cost
                           WHEN lr.returned_qty >= sl.quantity THEN 0::numeric(16,2)
                           ELSE ROUND(sl.line_cost * (sl.quantity - lr.returned_qty) / sl.quantity, 2)
                       END AS net_cost
                FROM sale_lines sl
                JOIN sales s ON s.id = sl.sale_id
                JOIN products p ON p.id = sl.product_id
                JOIN categories c ON c.id = p.category_id
                LEFT JOIN brands b ON b.id = p.brand_id
                LEFT JOIN line_returns lr ON lr.sale_line_id = sl.id
                WHERE sl.store_id IN (:storeIds)
                  AND sl.business_date BETWEEN :from AND :to
                  AND s.status <> 'VOIDED'
            )
            """;

    private final JdbcClient jdbc;

    public MarginReportRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Proyección cruda agregada por grupo antes de calcular margen y porcentaje. */
    public record MarginAggregateRow(
            String key,
            String label,
            BigDecimal qty,
            BigDecimal netSales,
            BigDecimal cost
    ) {
    }

    /**
     * Consulta los montos netos y costos snapshot agrupados según {@code groupBy}.
     *
     * @param from     primera fecha de negocio inclusive
     * @param to       última fecha de negocio inclusive
     * @param storeIds lista no vacía de identificadores de local
     * @param groupBy  criterio de agrupación
     * @return filas agregadas y ordenadas
     */
    public List<MarginAggregateRow> findMarginRows(
            LocalDate from,
            LocalDate to,
            List<Long> storeIds,
            MarginGroupBy groupBy) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(storeIds, "storeIds");
        Objects.requireNonNull(groupBy, "groupBy");
        if (storeIds.isEmpty()) {
            return List.of();
        }

        String selectAndGroup = switch (groupBy) {
            case PRODUCT -> """
                    SELECT CAST(product_id AS text) AS row_key,
                           product_name AS row_label,
                           COALESCE(SUM(net_qty), 0) AS qty,
                           COALESCE(SUM(net_sales), 0) AS net_sales,
                           COALESCE(SUM(net_cost), 0) AS cost
                    FROM net_lines
                    GROUP BY product_id, product_name
                    HAVING COALESCE(SUM(net_qty), 0) <> 0
                        OR COALESCE(SUM(net_sales), 0) <> 0
                        OR COALESCE(SUM(net_cost), 0) <> 0
                    ORDER BY (COALESCE(SUM(net_sales), 0) - COALESCE(SUM(net_cost), 0)) DESC,
                             row_label ASC, row_key ASC
                    """;
            case CATEGORY -> """
                    SELECT CAST(category_id AS text) AS row_key,
                           category_name AS row_label,
                           COALESCE(SUM(net_qty), 0) AS qty,
                           COALESCE(SUM(net_sales), 0) AS net_sales,
                           COALESCE(SUM(net_cost), 0) AS cost
                    FROM net_lines
                    GROUP BY category_id, category_name
                    HAVING COALESCE(SUM(net_qty), 0) <> 0
                        OR COALESCE(SUM(net_sales), 0) <> 0
                        OR COALESCE(SUM(net_cost), 0) <> 0
                    ORDER BY (COALESCE(SUM(net_sales), 0) - COALESCE(SUM(net_cost), 0)) DESC,
                             row_label ASC, row_key ASC
                    """;
            case BRAND -> """
                    SELECT COALESCE(CAST(brand_id AS text), 'NONE') AS row_key,
                           COALESCE(brand_name, 'Sin marca') AS row_label,
                           COALESCE(SUM(net_qty), 0) AS qty,
                           COALESCE(SUM(net_sales), 0) AS net_sales,
                           COALESCE(SUM(net_cost), 0) AS cost
                    FROM net_lines
                    GROUP BY brand_id, brand_name
                    HAVING COALESCE(SUM(net_qty), 0) <> 0
                        OR COALESCE(SUM(net_sales), 0) <> 0
                        OR COALESCE(SUM(net_cost), 0) <> 0
                    ORDER BY (COALESCE(SUM(net_sales), 0) - COALESCE(SUM(net_cost), 0)) DESC,
                             row_label ASC, row_key ASC
                    """;
            case DAY -> """
                    SELECT to_char(business_date, 'YYYY-MM-DD') AS row_key,
                           to_char(business_date, 'YYYY-MM-DD') AS row_label,
                           COALESCE(SUM(net_qty), 0) AS qty,
                           COALESCE(SUM(net_sales), 0) AS net_sales,
                           COALESCE(SUM(net_cost), 0) AS cost
                    FROM net_lines
                    GROUP BY business_date
                    HAVING COALESCE(SUM(net_qty), 0) <> 0
                        OR COALESCE(SUM(net_sales), 0) <> 0
                        OR COALESCE(SUM(net_cost), 0) <> 0
                    ORDER BY business_date ASC
                    """;
        };

        return jdbc.sql(BASE_CTE + selectAndGroup)
                .param("from", from)
                .param("to", to)
                .param("storeIds", storeIds)
                .query((rs, rowNum) -> new MarginAggregateRow(
                        rs.getString("row_key"),
                        rs.getString("row_label"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("net_sales"),
                        rs.getBigDecimal("cost")))
                .list();
    }
}
