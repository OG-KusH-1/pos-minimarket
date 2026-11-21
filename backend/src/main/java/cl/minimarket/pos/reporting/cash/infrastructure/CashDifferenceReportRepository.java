package cl.minimarket.pos.reporting.cash.infrastructure;

import cl.minimarket.pos.reporting.cash.CashDifferenceRow;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repositorio JDBC de solo lectura para el reporte de diferencias de caja ({@code ADR-11},
 * {@code HU-REP-09}).
 *
 * <p>Consulta turnos cerrados ({@code status = 'CLOSED'}) en {@code cash_sessions} para los
 * locales y rango de {@code business_date} indicados.
 */
@Repository
public class CashDifferenceReportRepository {

    private final JdbcClient jdbc;

    public CashDifferenceReportRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Consulta los turnos cerrados con sus totales de arqueo y aprobación de diferencia.
     *
     * @param from           primera fecha de negocio inclusive
     * @param to             última fecha de negocio inclusive
     * @param storeIds       lista no vacía de identificadores de local
     * @param cashierId      filtro opcional por usuario cajero
     * @param withDifference {@code true} para solo diferencias distintas de 0; {@code false} para
     *                       solo cuadres exactos; {@code null} para todos los turnos cerrados
     * @return filas ordenadas por fecha de negocio y cierre descendente
     */
    public List<CashDifferenceRow> findCashDifferences(
            LocalDate from,
            LocalDate to,
            List<Long> storeIds,
            Long cashierId,
            Boolean withDifference) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(storeIds, "storeIds");
        if (storeIds.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
                SELECT cs.id AS session_id,
                       cs.number,
                       cs.business_date,
                       COALESCE(NULLIF(BTRIM(uc.full_name), ''), uc.username) AS cashier,
                       t.name AS terminal,
                       COALESCE(cs.expected_total, 0) AS expected,
                       COALESCE(cs.counted_total, 0) AS counted,
                       COALESCE(cs.difference_total, 0) AS difference,
                       CASE
                           WHEN ua.id IS NOT NULL THEN COALESCE(NULLIF(BTRIM(ua.full_name), ''), ua.username)
                           ELSE NULL
                       END AS approved_by
                FROM cash_sessions cs
                JOIN terminals t ON t.id = cs.terminal_id
                JOIN users uc ON uc.id = cs.cashier_user_id
                LEFT JOIN users ua ON ua.id = cs.difference_approved_by
                WHERE cs.store_id IN (:storeIds)
                  AND cs.status = 'CLOSED'
                  AND cs.business_date BETWEEN :from AND :to
                """);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeIds", storeIds);
        params.put("from", from);
        params.put("to", to);

        if (cashierId != null) {
            sql.append(" AND cs.cashier_user_id = :cashierId ");
            params.put("cashierId", cashierId);
        }
        if (Boolean.TRUE.equals(withDifference)) {
            sql.append(" AND cs.difference_total IS NOT NULL AND cs.difference_total <> 0 ");
        } else if (Boolean.FALSE.equals(withDifference)) {
            sql.append(" AND (cs.difference_total IS NULL OR cs.difference_total = 0) ");
        }

        sql.append(" ORDER BY cs.business_date DESC, cs.closed_at DESC NULLS LAST, cs.id DESC ");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }

        return spec.query((rs, rowNum) -> new CashDifferenceRow(
                        rs.getLong("session_id"),
                        rs.getString("number"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getString("cashier"),
                        rs.getString("terminal"),
                        Rounding.clp(rs.getBigDecimal("expected")),
                        Rounding.clp(rs.getBigDecimal("counted")),
                        Rounding.clp(rs.getBigDecimal("difference")),
                        rs.getString("approved_by")))
                .list();
    }
}
