package cl.minimarket.pos.reporting.sales.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repositorio JDBC de solo lectura para los reportes de venta (ADR-11): consulta directamente las
 * tablas transaccionales ({@code sales}, {@code sale_lines}, {@code sale_returns},
 * {@code sale_return_lines}, {@code sale_payments}, {@code sale_return_refunds},
 * {@code supervisor_authorizations}) y maestras ({@code products}, {@code categories},
 * {@code stores}, {@code users}, {@code stock}) filtrando siempre por {@code store_id} y rango de
 * fechas para aprovechar los índices compuestos.
 */
@Repository
public class SalesReportsRepository {

    private static final String SCOPED_SALES_CTES = """
            WITH scoped_sales AS (
                SELECT s.id,
                       s.store_id,
                       s.status,
                       s.sold_at,
                       s.business_date,
                       s.discount_total,
                       s.total_gross,
                       s.tax_total
                FROM sales s
                WHERE s.store_id IN (:storeIds)
                  AND s.business_date BETWEEN :from AND :to
            ),
            returns_by_sale AS (
                SELECT sl.sale_id,
                       COALESCE(SUM(srl.refund_amount), 0) AS refund_amount,
                       COALESCE(SUM(srl.refund_tax), 0) AS refund_tax
                FROM sale_return_lines srl
                JOIN sale_lines sl ON sl.id = srl.sale_line_id
                WHERE sl.sale_id IN (SELECT id FROM scoped_sales)
                GROUP BY sl.sale_id
            )
            """;

    private final JdbcClient jdbc;

    public SalesReportsRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Fila cruda agregada del reporte de ventas antes de calcular netos y ticket promedio. */
    public record RawSalesGroupRow(
            String key,
            String label,
            long transactions,
            BigDecimal grossTotal,
            BigDecimal taxTotal,
            BigDecimal discountTotal,
            BigDecimal returnsTotal
    ) {
    }

    /** Fila cruda de ventas por cajero antes de calcular ticket promedio. */
    public record RawCashierRow(
            Long userId,
            String name,
            long transactions,
            BigDecimal total,
            long voids,
            long returns,
            BigDecimal discountTotal,
            long overridesCount
    ) {
    }

    /** Fila cruda de ventas netas por medio de pago. */
    public record RawPaymentMethodRow(
            String method,
            long transactions,
            BigDecimal total
    ) {
    }

    /** Fila cruda de ranking de productos antes de asignar {@code rank}. */
    public record RawProductRankingRow(
            Long productId,
            String sku,
            String name,
            String category,
            BigDecimal qty,
            BigDecimal netSales,
            long transactions
    ) {
    }

    /** Fila cruda de productos con stock sin rotación reciente. */
    public record RawNoRotationRow(
            Long productId,
            String sku,
            String name,
            BigDecimal stockQty,
            BigDecimal avgCost,
            LocalDate lastSaleDate,
            Instant lastSaleAt
    ) {
    }

    /** Fila cruda de ventas netas y costo por producto para el análisis ABC. */
    public record RawAbcProductRow(
            Long productId,
            String sku,
            String name,
            BigDecimal netQty,
            BigDecimal netSales,
            BigDecimal netCost
    ) {
    }

    /**
     * Consulta ventas netas de devoluciones agrupadas por día, hora, día de semana, categoría o
     * local.
     */
    public List<RawSalesGroupRow> findSalesGrouped(
            LocalDate from,
            LocalDate to,
            List<Long> storeIds,
            SalesGroupBy groupBy,
            String businessZoneId
    ) {
        if (groupBy == SalesGroupBy.CATEGORY) {
            return findSalesByCategory(from, to, storeIds);
        }
        String sql = switch (groupBy) {
            case DAY -> SCOPED_SALES_CTES + """
                    SELECT s.business_date::text AS group_key,
                           s.business_date::text AS group_label,
                           COUNT(*) FILTER (WHERE s.status <> 'VOIDED') AS transactions,
                           COALESCE(SUM(s.total_gross), 0) - COALESCE(SUM(r.refund_amount), 0) AS gross_total,
                           COALESCE(SUM(s.tax_total), 0) - COALESCE(SUM(r.refund_tax), 0) AS tax_total,
                           COALESCE(SUM(s.discount_total) FILTER (WHERE s.status <> 'VOIDED'), 0) AS discount_total,
                           COALESCE(SUM(r.refund_amount), 0) AS returns_total
                    FROM scoped_sales s
                    LEFT JOIN returns_by_sale r ON r.sale_id = s.id
                    GROUP BY s.business_date
                    ORDER BY s.business_date ASC
                    """;
            case HOUR -> SCOPED_SALES_CTES + """
                    , sales_by_hour AS (
                        SELECT EXTRACT(HOUR FROM (s.sold_at AT TIME ZONE CAST(:zone AS text)))::int AS sale_hour,
                               s.status,
                               s.total_gross,
                               s.tax_total,
                               s.discount_total,
                               COALESCE(r.refund_amount, 0) AS refund_amount,
                               COALESCE(r.refund_tax, 0) AS refund_tax
                        FROM scoped_sales s
                        LEFT JOIN returns_by_sale r ON r.sale_id = s.id
                    )
                    SELECT LPAD(sale_hour::text, 2, '0') AS group_key,
                           LPAD(sale_hour::text, 2, '0') || ':00' AS group_label,
                           COUNT(*) FILTER (WHERE status <> 'VOIDED') AS transactions,
                           COALESCE(SUM(total_gross), 0) - COALESCE(SUM(refund_amount), 0) AS gross_total,
                           COALESCE(SUM(tax_total), 0) - COALESCE(SUM(refund_tax), 0) AS tax_total,
                           COALESCE(SUM(discount_total) FILTER (WHERE status <> 'VOIDED'), 0) AS discount_total,
                           COALESCE(SUM(refund_amount), 0) AS returns_total
                    FROM sales_by_hour
                    GROUP BY sale_hour
                    ORDER BY sale_hour ASC
                    """;
            case WEEKDAY -> SCOPED_SALES_CTES + """
                    , sales_by_weekday AS (
                        SELECT EXTRACT(ISODOW FROM s.business_date)::int AS iso_dow,
                               s.status,
                               s.total_gross,
                               s.tax_total,
                               s.discount_total,
                               COALESCE(r.refund_amount, 0) AS refund_amount,
                               COALESCE(r.refund_tax, 0) AS refund_tax
                        FROM scoped_sales s
                        LEFT JOIN returns_by_sale r ON r.sale_id = s.id
                    )
                    SELECT iso_dow::text AS group_key,
                           iso_dow::text AS group_label,
                           COUNT(*) FILTER (WHERE status <> 'VOIDED') AS transactions,
                           COALESCE(SUM(total_gross), 0) - COALESCE(SUM(refund_amount), 0) AS gross_total,
                           COALESCE(SUM(tax_total), 0) - COALESCE(SUM(refund_tax), 0) AS tax_total,
                           COALESCE(SUM(discount_total) FILTER (WHERE status <> 'VOIDED'), 0) AS discount_total,
                           COALESCE(SUM(refund_amount), 0) AS returns_total
                    FROM sales_by_weekday
                    GROUP BY iso_dow
                    ORDER BY iso_dow ASC
                    """;
            case STORE -> SCOPED_SALES_CTES + """
                    SELECT st.id::text AS group_key,
                           st.name AS group_label,
                           COUNT(*) FILTER (WHERE s.status <> 'VOIDED') AS transactions,
                           COALESCE(SUM(s.total_gross), 0) - COALESCE(SUM(r.refund_amount), 0) AS gross_total,
                           COALESCE(SUM(s.tax_total), 0) - COALESCE(SUM(r.refund_tax), 0) AS tax_total,
                           COALESCE(SUM(s.discount_total) FILTER (WHERE s.status <> 'VOIDED'), 0) AS discount_total,
                           COALESCE(SUM(r.refund_amount), 0) AS returns_total
                    FROM scoped_sales s
                    JOIN stores st ON st.id = s.store_id
                    LEFT JOIN returns_by_sale r ON r.sale_id = s.id
                    GROUP BY st.id, st.name
                    ORDER BY gross_total DESC, st.name ASC
                    """;
            case CATEGORY -> throw new IllegalStateException("Rama manejada arriba");
        };

        JdbcClient.StatementSpec spec = jdbc.sql(sql)
                .param("storeIds", storeIds)
                .param("from", from)
                .param("to", to);
        if (groupBy == SalesGroupBy.HOUR) {
            spec = spec.param("zone", businessZoneId);
        }
        return spec.query((rs, rowNum) -> new RawSalesGroupRow(
                        rs.getString("group_key"),
                        rs.getString("group_label"),
                        rs.getLong("transactions"),
                        rs.getBigDecimal("gross_total"),
                        rs.getBigDecimal("tax_total"),
                        rs.getBigDecimal("discount_total"),
                        rs.getBigDecimal("returns_total")))
                .list();
    }

    private List<RawSalesGroupRow> findSalesByCategory(LocalDate from, LocalDate to, List<Long> storeIds) {
        return jdbc.sql("""
                        WITH scoped_lines AS (
                            SELECT sl.id,
                                   sl.sale_id,
                                   sl.product_id,
                                   sl.discount_amount,
                                   sl.line_total_gross,
                                   sl.line_tax,
                                   s.status
                            FROM sale_lines sl
                            JOIN sales s ON s.id = sl.sale_id
                            WHERE sl.store_id IN (:storeIds)
                              AND sl.business_date BETWEEN :from AND :to
                        ),
                        returns_by_line AS (
                            SELECT srl.sale_line_id,
                                   COALESCE(SUM(srl.refund_amount), 0) AS refund_amount,
                                   COALESCE(SUM(srl.refund_tax), 0) AS refund_tax
                            FROM sale_return_lines srl
                            WHERE srl.sale_line_id IN (SELECT id FROM scoped_lines)
                            GROUP BY srl.sale_line_id
                        )
                        SELECT c.id::text AS group_key,
                               c.name AS group_label,
                               COUNT(DISTINCT CASE WHEN sl.status <> 'VOIDED' THEN sl.sale_id END) AS transactions,
                               COALESCE(SUM(sl.line_total_gross), 0) - COALESCE(SUM(r.refund_amount), 0) AS gross_total,
                               COALESCE(SUM(sl.line_tax), 0) - COALESCE(SUM(r.refund_tax), 0) AS tax_total,
                               COALESCE(SUM(sl.discount_amount) FILTER (WHERE sl.status <> 'VOIDED'), 0) AS discount_total,
                               COALESCE(SUM(r.refund_amount), 0) AS returns_total
                        FROM scoped_lines sl
                        JOIN products p ON p.id = sl.product_id
                        JOIN categories c ON c.id = p.category_id
                        LEFT JOIN returns_by_line r ON r.sale_line_id = sl.id
                        GROUP BY c.id, c.name
                        ORDER BY gross_total DESC, c.name ASC
                        """)
                .param("storeIds", storeIds)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new RawSalesGroupRow(
                        rs.getString("group_key"),
                        rs.getString("group_label"),
                        rs.getLong("transactions"),
                        rs.getBigDecimal("gross_total"),
                        rs.getBigDecimal("tax_total"),
                        rs.getBigDecimal("discount_total"),
                        rs.getBigDecimal("returns_total")))
                .list();
    }

    /**
     * Cuenta transacciones distintas no anuladas en los locales y fechas indicados (evita doble
     * conteo cuando {@code groupBy=category} parte una venta en múltiples categorías).
     */
    public long countDistinctNonVoidedSales(LocalDate from, LocalDate to, List<Long> storeIds) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM sales s
                        WHERE s.store_id IN (:storeIds)
                          AND s.business_date BETWEEN :from AND :to
                          AND s.status <> 'VOIDED'
                        """)
                .param("storeIds", storeIds)
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /**
     * Consulta ventas netas, descuentos, anulaciones, devoluciones y overrides por cajero.
     */
    public List<RawCashierRow> findSalesByCashier(
            LocalDate from,
            LocalDate to,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            List<Long> storeIds
    ) {
        return jdbc.sql("""
                        WITH scoped_sales AS (
                            SELECT s.id,
                                   s.cashier_user_id,
                                   s.status,
                                   s.discount_total,
                                   s.total_gross
                            FROM sales s
                            WHERE s.store_id IN (:storeIds)
                              AND s.business_date BETWEEN :from AND :to
                        ),
                        returns_by_sale AS (
                            SELECT sl.sale_id,
                                   COALESCE(SUM(srl.refund_amount), 0) AS refund_amount
                            FROM sale_return_lines srl
                            JOIN sale_lines sl ON sl.id = srl.sale_line_id
                            WHERE sl.sale_id IN (SELECT id FROM scoped_sales)
                            GROUP BY sl.sale_id
                        ),
                        return_counts_by_cashier AS (
                            SELECT s.cashier_user_id AS user_id,
                                   COUNT(*) FILTER (WHERE sr.return_type = 'VOID') AS voids,
                                   COUNT(*) FILTER (WHERE sr.return_type = 'RETURN') AS returns
                            FROM sale_returns sr
                            JOIN scoped_sales s ON s.id = sr.sale_id
                            GROUP BY s.cashier_user_id
                        ),
                        sales_by_cashier AS (
                            SELECT s.cashier_user_id AS user_id,
                                   COUNT(*) FILTER (WHERE s.status <> 'VOIDED') AS transactions,
                                   COALESCE(SUM(s.total_gross), 0) - COALESCE(SUM(r.refund_amount), 0) AS total,
                                   COALESCE(SUM(s.discount_total) FILTER (WHERE s.status <> 'VOIDED'), 0) AS discount_total
                            FROM scoped_sales s
                            LEFT JOIN returns_by_sale r ON r.sale_id = s.id
                            GROUP BY s.cashier_user_id
                        ),
                        overrides_by_cashier AS (
                            SELECT sa.requested_by AS user_id,
                                   COUNT(*) AS overrides_count
                            FROM supervisor_authorizations sa
                            WHERE sa.store_id IN (:storeIds)
                              AND sa.status = 'GRANTED'
                              AND sa.created_at >= :windowStart
                              AND sa.created_at < :windowEnd
                            GROUP BY sa.requested_by
                        ),
                        cashier_ids AS (
                            SELECT user_id FROM sales_by_cashier
                            UNION
                            SELECT user_id FROM overrides_by_cashier
                        )
                        SELECT u.id AS user_id,
                               COALESCE(NULLIF(BTRIM(u.full_name), ''), u.username) AS name,
                               COALESCE(sc.transactions, 0) AS transactions,
                               COALESCE(sc.total, 0) AS total,
                               COALESCE(rc.voids, 0) AS voids,
                               COALESCE(rc.returns, 0) AS returns,
                               COALESCE(sc.discount_total, 0) AS discount_total,
                               COALESCE(oc.overrides_count, 0) AS overrides_count
                        FROM cashier_ids cid
                        JOIN users u ON u.id = cid.user_id
                        LEFT JOIN sales_by_cashier sc ON sc.user_id = cid.user_id
                        LEFT JOIN return_counts_by_cashier rc ON rc.user_id = cid.user_id
                        LEFT JOIN overrides_by_cashier oc ON oc.user_id = cid.user_id
                        ORDER BY total DESC, transactions DESC, name ASC, u.id ASC
                        """)
                .param("storeIds", storeIds)
                .param("from", from)
                .param("to", to)
                .param("windowStart", windowStart, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("windowEnd", windowEnd, Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs, rowNum) -> new RawCashierRow(
                        rs.getLong("user_id"),
                        rs.getString("name"),
                        rs.getLong("transactions"),
                        rs.getBigDecimal("total"),
                        rs.getLong("voids"),
                        rs.getLong("returns"),
                        rs.getBigDecimal("discount_total"),
                        rs.getLong("overrides_count")))
                .list();
    }

    /**
     * Consulta pagos netos de reembolsos por medio de pago (excluye ventas {@code VOIDED} porque
     * sus pagos quedan en estado {@code REVERSED}).
     */
    public List<RawPaymentMethodRow> findSalesByPaymentMethod(
            LocalDate from,
            LocalDate to,
            List<Long> storeIds
    ) {
        return jdbc.sql("""
                        WITH scoped_sales AS (
                            SELECT s.id
                            FROM sales s
                            WHERE s.store_id IN (:storeIds)
                              AND s.business_date BETWEEN :from AND :to
                              AND s.status <> 'VOIDED'
                        ),
                        payments_by_method AS (
                            SELECT sp.method,
                                   COUNT(DISTINCT sp.sale_id) AS transactions,
                                   COALESCE(SUM(sp.amount), 0) AS paid_amount
                            FROM sale_payments sp
                            JOIN scoped_sales s ON s.id = sp.sale_id
                            WHERE sp.status = 'APPROVED'
                            GROUP BY sp.method
                        ),
                        refunds_by_method AS (
                            SELECT srr.method,
                                   COALESCE(SUM(srr.amount), 0) AS refunded_amount
                            FROM sale_return_refunds srr
                            JOIN sale_returns sr ON sr.id = srr.sale_return_id
                            JOIN scoped_sales s ON s.id = sr.sale_id
                            WHERE sr.return_type = 'RETURN'
                            GROUP BY srr.method
                        ),
                        methods AS (
                            SELECT method FROM payments_by_method
                            UNION
                            SELECT method FROM refunds_by_method
                        )
                        SELECT m.method,
                               COALESCE(p.transactions, 0) AS transactions,
                               COALESCE(p.paid_amount, 0) - COALESCE(r.refunded_amount, 0) AS total
                        FROM methods m
                        LEFT JOIN payments_by_method p ON p.method = m.method
                        LEFT JOIN refunds_by_method r ON r.method = m.method
                        ORDER BY total DESC, m.method ASC
                        """)
                .param("storeIds", storeIds)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new RawPaymentMethodRow(
                        rs.getString("method"),
                        rs.getLong("transactions"),
                        rs.getBigDecimal("total")))
                .list();
    }

    /**
     * Cuenta ventas distintas no anuladas que registraron al menos un pago aprobado en el rango.
     */
    public long countDistinctSalesWithApprovedPayments(LocalDate from, LocalDate to, List<Long> storeIds) {
        Long count = jdbc.sql("""
                        SELECT COUNT(DISTINCT sp.sale_id)
                        FROM sale_payments sp
                        JOIN sales s ON s.id = sp.sale_id
                        WHERE s.store_id IN (:storeIds)
                          AND s.business_date BETWEEN :from AND :to
                          AND s.status <> 'VOIDED'
                          AND sp.status = 'APPROVED'
                        """)
                .param("storeIds", storeIds)
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /**
     * Consulta los productos más o menos vendidos en el rango, con filtro opcional por categoría
     * (incluyendo subcategorías).
     */
    public List<RawProductRankingRow> findProductRanking(
            LocalDate from,
            LocalDate to,
            List<Long> storeIds,
            RankingOrder order,
            RankingMetric metric,
            int limit,
            Long categoryId
    ) {
        String direction = order == RankingOrder.MOST ? "DESC" : "ASC";
        String orderClause = metric == RankingMetric.QTY
                ? "qty " + direction + ", net_sales " + direction + ", p.name ASC, p.id ASC"
                : "net_sales " + direction + ", qty " + direction + ", p.name ASC, p.id ASC";

        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeIds", storeIds);
        params.put("from", from);
        params.put("to", to);
        params.put("limit", limit);

        if (categoryId != null) {
            sql.append("""
                    WITH RECURSIVE category_tree AS (
                        SELECT id
                        FROM categories
                        WHERE id = :categoryId
                        UNION
                        SELECT c.id
                        FROM categories c
                        JOIN category_tree ct ON c.parent_id = ct.id
                    ),
                    """);
            params.put("categoryId", categoryId);
        } else {
            sql.append("WITH ");
        }

        sql.append("""
                scoped_lines AS (
                    SELECT sl.id,
                           sl.sale_id,
                           sl.product_id,
                           sl.quantity,
                           sl.line_total_gross,
                           sl.line_tax,
                           s.status
                    FROM sale_lines sl
                    JOIN sales s ON s.id = sl.sale_id
                    WHERE sl.store_id IN (:storeIds)
                      AND sl.business_date BETWEEN :from AND :to
                ),
                returns_by_line AS (
                    SELECT srl.sale_line_id,
                           COALESCE(SUM(srl.quantity), 0) AS qty_returned,
                           COALESCE(SUM(srl.refund_amount), 0) AS refund_amount,
                           COALESCE(SUM(srl.refund_tax), 0) AS refund_tax
                    FROM sale_return_lines srl
                    WHERE srl.sale_line_id IN (SELECT id FROM scoped_lines)
                    GROUP BY srl.sale_line_id
                )
                SELECT p.id AS product_id,
                       p.sku,
                       p.name,
                       c.name AS category,
                       COALESCE(SUM(sl.quantity), 0) - COALESCE(SUM(r.qty_returned), 0) AS qty,
                       (COALESCE(SUM(sl.line_total_gross), 0) - COALESCE(SUM(sl.line_tax), 0))
                         - (COALESCE(SUM(r.refund_amount), 0) - COALESCE(SUM(r.refund_tax), 0)) AS net_sales,
                       COUNT(DISTINCT CASE WHEN sl.status <> 'VOIDED' THEN sl.sale_id END) AS transactions
                FROM scoped_lines sl
                JOIN products p ON p.id = sl.product_id
                JOIN categories c ON c.id = p.category_id
                LEFT JOIN returns_by_line r ON r.sale_line_id = sl.id
                """);

        if (categoryId != null) {
            sql.append("WHERE c.id IN (SELECT id FROM category_tree)\n");
        }

        sql.append("""
                GROUP BY p.id, p.sku, p.name, c.name
                HAVING (COALESCE(SUM(sl.quantity), 0) - COALESCE(SUM(r.qty_returned), 0)) > 0
                """);
        sql.append("ORDER BY ").append(orderClause).append("\nLIMIT :limit\n");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query((rs, rowNum) -> new RawProductRankingRow(
                        rs.getLong("product_id"),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("net_sales"),
                        rs.getLong("transactions")))
                .list();
    }

    /**
     * Consulta productos activos no pack con stock positivo y sin ventas netas en la ventana
     * {@code [windowStart, to]} (consolidando packs vía {@code stock_product_id}).
     */
    public List<RawNoRotationRow> findNoRotation(
            LocalDate to,
            LocalDate windowStart,
            List<Long> storeIds
    ) {
        return jdbc.sql("""
                        WITH stocked_products AS (
                            SELECT s.product_id,
                                   p.sku,
                                   p.name,
                                   SUM(s.quantity) AS stock_qty,
                                   p.avg_cost
                            FROM stock s
                            JOIN products p ON p.id = s.product_id
                            JOIN stores st ON st.id = s.store_id
                            WHERE s.store_id IN (:storeIds)
                              AND st.active = true
                              AND p.active = true
                              AND p.sale_type <> 'PACK'
                            GROUP BY s.product_id, p.sku, p.name, p.avg_cost
                            HAVING SUM(s.quantity) > 0
                        ),
                        last_sales AS (
                            SELECT sl.stock_product_id AS product_id,
                                   MAX(s.business_date) AS last_sale_date,
                                   MAX(s.sold_at) AS last_sale_at
                            FROM sale_lines sl
                            JOIN sales s ON s.id = sl.sale_id
                            WHERE sl.stock_product_id IN (SELECT product_id FROM stocked_products)
                              AND sl.store_id IN (:storeIds)
                              AND sl.business_date <= :to
                              AND s.status <> 'VOIDED'
                              AND sl.quantity > COALESCE((
                                  SELECT SUM(srl.quantity)
                                  FROM sale_return_lines srl
                                  WHERE srl.sale_line_id = sl.id
                              ), 0)
                            GROUP BY sl.stock_product_id
                        )
                        SELECT sp.product_id,
                               sp.sku,
                               sp.name,
                               sp.stock_qty,
                               sp.avg_cost,
                               ls.last_sale_date,
                               ls.last_sale_at
                        FROM stocked_products sp
                        LEFT JOIN last_sales ls ON ls.product_id = sp.product_id
                        WHERE ls.last_sale_date IS NULL
                           OR ls.last_sale_date < :windowStart
                        ORDER BY ls.last_sale_date ASC NULLS FIRST,
                                 (sp.stock_qty * sp.avg_cost) DESC,
                                 sp.name ASC,
                                 sp.product_id ASC
                        """)
                .param("storeIds", storeIds)
                .param("to", to)
                .param("windowStart", windowStart)
                .query((rs, rowNum) -> {
                    OffsetDateTime odt = rs.getObject("last_sale_at", OffsetDateTime.class);
                    return new RawNoRotationRow(
                            rs.getLong("product_id"),
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getBigDecimal("stock_qty"),
                            rs.getBigDecimal("avg_cost"),
                            rs.getObject("last_sale_date", LocalDate.class),
                            odt != null ? odt.toInstant() : null);
                })
                .list();
    }

    /**
     * Consulta ventas netas (cantidad, neto sin impuestos y costo snapshot neto de devoluciones)
     * por producto vendido para el análisis Pareto ABC.
     */
    public List<RawAbcProductRow> findAbcCandidates(LocalDate from, LocalDate to, List<Long> storeIds) {
        return jdbc.sql("""
                        WITH scoped_lines AS (
                            SELECT sl.id,
                                   sl.product_id,
                                   sl.quantity,
                                   sl.line_total_gross,
                                   sl.line_tax,
                                   sl.unit_cost,
                                   sl.line_cost
                            FROM sale_lines sl
                            WHERE sl.store_id IN (:storeIds)
                              AND sl.business_date BETWEEN :from AND :to
                        ),
                        returns_by_line AS (
                            SELECT srl.sale_line_id,
                                   COALESCE(SUM(srl.quantity), 0) AS qty_returned,
                                   COALESCE(SUM(srl.refund_amount), 0) AS refund_amount,
                                   COALESCE(SUM(srl.refund_tax), 0) AS refund_tax
                            FROM sale_return_lines srl
                            WHERE srl.sale_line_id IN (SELECT id FROM scoped_lines)
                            GROUP BY srl.sale_line_id
                        )
                        SELECT p.id AS product_id,
                               p.sku,
                               p.name,
                               COALESCE(SUM(sl.quantity), 0) - COALESCE(SUM(r.qty_returned), 0) AS net_qty,
                               (COALESCE(SUM(sl.line_total_gross), 0) - COALESCE(SUM(sl.line_tax), 0))
                                 - (COALESCE(SUM(r.refund_amount), 0) - COALESCE(SUM(r.refund_tax), 0)) AS net_sales,
                               COALESCE(SUM(sl.line_cost), 0)
                                 - COALESCE(SUM(
                                     CASE WHEN COALESCE(r.qty_returned, 0) >= sl.quantity THEN sl.line_cost
                                          ELSE ROUND(sl.unit_cost * COALESCE(r.qty_returned, 0), 2)
                                     END
                                 ), 0) AS net_cost
                        FROM scoped_lines sl
                        JOIN products p ON p.id = sl.product_id
                        LEFT JOIN returns_by_line r ON r.sale_line_id = sl.id
                        GROUP BY p.id, p.sku, p.name
                        """)
                .param("storeIds", storeIds)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new RawAbcProductRow(
                        rs.getLong("product_id"),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getBigDecimal("net_qty"),
                        rs.getBigDecimal("net_sales"),
                        rs.getBigDecimal("net_cost")))
                .list();
    }
}
