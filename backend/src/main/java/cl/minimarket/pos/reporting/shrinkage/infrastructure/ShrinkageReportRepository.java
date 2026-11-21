package cl.minimarket.pos.reporting.shrinkage.infrastructure;

import cl.minimarket.pos.reporting.shrinkage.ShrinkageGroupBy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Repositorio JDBC de solo lectura para los reportes de mermas ({@code ADR-11}, {@code MODELO_DATOS}
 * B.6, {@code HU-MER-04}, {@code HU-MER-05}).
 *
 * <p>Contabiliza exclusivamente filas de {@code shrinkages} con {@code status = 'APPROVED'} y
 * consulta las ventas netas del mismo período y locales para calcular {@code pctOfSales}.
 */
@Repository
public class ShrinkageReportRepository {

    private final JdbcClient jdbc;

    public ShrinkageReportRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Proyección cruda agregada de mermas aprobadas. */
    public record ShrinkageAggregateRow(
            String key,
            String label,
            BigDecimal qty,
            BigDecimal cost,
            long count
    ) {
    }

    /**
     * Consulta mermas {@code APPROVED} en {@code [fromInstant, toExclusive)} agrupadas según
     * {@code groupBy}.
     *
     * @param fromInstant instante UTC inclusivo de inicio
     * @param toExclusive instante UTC exclusivo de término
     * @param storeIds    lista no vacía de locales
     * @param groupBy     criterio de agrupación
     * @param zoneId      identificador de zona IANA para agrupar por mes en hora de negocio
     * @return filas agregadas y ordenadas
     */
    public List<ShrinkageAggregateRow> findShrinkageRows(
            Instant fromInstant,
            Instant toExclusive,
            List<Long> storeIds,
            ShrinkageGroupBy groupBy,
            String zoneId) {
        Objects.requireNonNull(fromInstant, "fromInstant");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(storeIds, "storeIds");
        Objects.requireNonNull(groupBy, "groupBy");
        Objects.requireNonNull(zoneId, "zoneId");
        if (storeIds.isEmpty()) {
            return List.of();
        }

        OffsetDateTime fromUtc = OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC);
        OffsetDateTime toUtc = OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC);

        if (groupBy == ShrinkageGroupBy.MONTH) {
            return jdbc.sql("""
                            WITH monthly AS (
                                SELECT to_char(sh.occurred_at AT TIME ZONE :zoneId, 'YYYY-MM') AS month_key,
                                       sh.quantity,
                                       sh.total_cost
                                FROM shrinkages sh
                                WHERE sh.store_id IN (:storeIds)
                                  AND sh.status = 'APPROVED'
                                  AND sh.occurred_at >= :fromUtc
                                  AND sh.occurred_at < :toUtc
                            )
                            SELECT month_key AS row_key,
                                   month_key AS row_label,
                                   COALESCE(SUM(quantity), 0) AS qty,
                                   COALESCE(SUM(total_cost), 0) AS cost,
                                   COUNT(*) AS cnt
                            FROM monthly
                            GROUP BY month_key
                            ORDER BY month_key ASC
                            """)
                    .param("zoneId", zoneId)
                    .param("storeIds", storeIds)
                    .param("fromUtc", fromUtc)
                    .param("toUtc", toUtc)
                    .query(ShrinkageReportRepository::mapRow)
                    .list();
        }

        String sql = switch (groupBy) {
            case TYPE -> """
                    SELECT sh.shrinkage_type AS row_key,
                           sh.shrinkage_type AS row_label,
                           COALESCE(SUM(sh.quantity), 0) AS qty,
                           COALESCE(SUM(sh.total_cost), 0) AS cost,
                           COUNT(*) AS cnt
                    FROM shrinkages sh
                    WHERE sh.store_id IN (:storeIds)
                      AND sh.status = 'APPROVED'
                      AND sh.occurred_at >= :fromUtc
                      AND sh.occurred_at < :toUtc
                    GROUP BY sh.shrinkage_type
                    ORDER BY cost DESC, cnt DESC, row_key ASC
                    """;
            case PRODUCT -> """
                    SELECT CAST(p.id AS text) AS row_key,
                           p.name AS row_label,
                           COALESCE(SUM(sh.quantity), 0) AS qty,
                           COALESCE(SUM(sh.total_cost), 0) AS cost,
                           COUNT(*) AS cnt
                    FROM shrinkages sh
                    JOIN products p ON p.id = sh.product_id
                    WHERE sh.store_id IN (:storeIds)
                      AND sh.status = 'APPROVED'
                      AND sh.occurred_at >= :fromUtc
                      AND sh.occurred_at < :toUtc
                    GROUP BY p.id, p.name
                    ORDER BY cost DESC, cnt DESC, row_label ASC, row_key ASC
                    """;
            case CATEGORY -> """
                    SELECT CAST(c.id AS text) AS row_key,
                           c.name AS row_label,
                           COALESCE(SUM(sh.quantity), 0) AS qty,
                           COALESCE(SUM(sh.total_cost), 0) AS cost,
                           COUNT(*) AS cnt
                    FROM shrinkages sh
                    JOIN products p ON p.id = sh.product_id
                    JOIN categories c ON c.id = p.category_id
                    WHERE sh.store_id IN (:storeIds)
                      AND sh.status = 'APPROVED'
                      AND sh.occurred_at >= :fromUtc
                      AND sh.occurred_at < :toUtc
                    GROUP BY c.id, c.name
                    ORDER BY cost DESC, cnt DESC, row_label ASC, row_key ASC
                    """;
            case STORE -> """
                    SELECT CAST(st.id AS text) AS row_key,
                           st.name AS row_label,
                           COALESCE(SUM(sh.quantity), 0) AS qty,
                           COALESCE(SUM(sh.total_cost), 0) AS cost,
                           COUNT(*) AS cnt
                    FROM shrinkages sh
                    JOIN stores st ON st.id = sh.store_id
                    WHERE sh.store_id IN (:storeIds)
                      AND sh.status = 'APPROVED'
                      AND sh.occurred_at >= :fromUtc
                      AND sh.occurred_at < :toUtc
                    GROUP BY st.id, st.name
                    ORDER BY cost DESC, cnt DESC, row_label ASC, row_key ASC
                    """;
            case MONTH -> throw new IllegalStateException("MONTH se resuelve por rama dedicada");
        };

        return jdbc.sql(sql)
                .param("storeIds", storeIds)
                .param("fromUtc", fromUtc)
                .param("toUtc", toUtc)
                .query(ShrinkageReportRepository::mapRow)
                .list();
    }

    /**
     * Consulta los {@code limit} productos con mayor costo acumulado de mermas aprobadas en el rango.
     */
    public List<ShrinkageAggregateRow> findTopProducts(
            Instant fromInstant,
            Instant toExclusive,
            List<Long> storeIds,
            int limit) {
        Objects.requireNonNull(fromInstant, "fromInstant");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(storeIds, "storeIds");
        if (storeIds.isEmpty() || limit <= 0) {
            return List.of();
        }

        OffsetDateTime fromUtc = OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC);
        OffsetDateTime toUtc = OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC);

        return jdbc.sql("""
                        SELECT CAST(p.id AS text) AS row_key,
                               p.name AS row_label,
                               COALESCE(SUM(sh.quantity), 0) AS qty,
                               COALESCE(SUM(sh.total_cost), 0) AS cost,
                               COUNT(*) AS cnt
                        FROM shrinkages sh
                        JOIN products p ON p.id = sh.product_id
                        WHERE sh.store_id IN (:storeIds)
                          AND sh.status = 'APPROVED'
                          AND sh.occurred_at >= :fromUtc
                          AND sh.occurred_at < :toUtc
                        GROUP BY p.id, p.name
                        ORDER BY cost DESC, qty DESC, row_label ASC, row_key ASC
                        LIMIT :limit
                        """)
                .param("storeIds", storeIds)
                .param("fromUtc", fromUtc)
                .param("toUtc", toUtc)
                .param("limit", limit)
                .query(ShrinkageReportRepository::mapRow)
                .list();
    }

    /**
     * Calcula las ventas netas sin impuestos (afectas y exentas, netas de devoluciones y anulaciones)
     * del mismo rango de fechas de negocio y locales, como denominador de {@code pctOfSales}.
     */
    public BigDecimal findTotalNetSales(LocalDate from, LocalDate to, List<Long> storeIds) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(storeIds, "storeIds");
        if (storeIds.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal result = jdbc.sql("""
                        WITH line_returns AS (
                            SELECT srl.sale_line_id,
                                   SUM(srl.refund_amount - srl.refund_tax) AS returned_net
                            FROM sale_return_lines srl
                            JOIN sale_lines sl ON sl.id = srl.sale_line_id
                            JOIN sales s ON s.id = sl.sale_id
                            WHERE sl.store_id IN (:storeIds)
                              AND sl.business_date BETWEEN :from AND :to
                              AND s.status <> 'VOIDED'
                            GROUP BY srl.sale_line_id
                        )
                        SELECT COALESCE(
                            SUM((sl.line_net + sl.line_exempt) - COALESCE(lr.returned_net, 0)),
                            0
                        )
                        FROM sale_lines sl
                        JOIN sales s ON s.id = sl.sale_id
                        LEFT JOIN line_returns lr ON lr.sale_line_id = sl.id
                        WHERE sl.store_id IN (:storeIds)
                          AND sl.business_date BETWEEN :from AND :to
                          AND s.status <> 'VOIDED'
                        """)
                .param("from", from)
                .param("to", to)
                .param("storeIds", storeIds)
                .query(BigDecimal.class)
                .single();
        return result != null ? result : BigDecimal.ZERO;
    }

    private static ShrinkageAggregateRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ShrinkageAggregateRow(
                rs.getString("row_key"),
                rs.getString("row_label"),
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("cost"),
                rs.getLong("cnt"));
    }
}
