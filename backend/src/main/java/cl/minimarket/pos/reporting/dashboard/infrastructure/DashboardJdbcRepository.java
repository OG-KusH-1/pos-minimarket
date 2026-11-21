package cl.minimarket.pos.reporting.dashboard.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Repositorio SQL de solo lectura para el dashboard diario ({@code ADR-11}, {@code API.md} F.2/F.3).
 *
 * <p>El módulo {@code reporting} es el único autorizado a leer por SQL las tablas transaccionales de
 * otros módulos ({@code sales}, {@code sale_lines}, {@code sale_payments}, {@code sale_returns},
 * {@code sale_return_lines}, {@code sale_return_refunds}, {@code stock}, {@code lots},
 * {@code shrinkages}, {@code stock_adjustments}, {@code inventory_counts}, {@code cash_sessions},
 * {@code settings}, {@code stores}, {@code user_stores}).
 *
 * <p>Todas las consultas filtran por {@code store_id IN (:storeIds)} y {@code business_date} para
 * aprovechar los índices existentes ({@code ix_sales_store_date}, {@code ix_sale_lines_store_date_product},
 * {@code ix_stock_low}, {@code ix_lots_expiry}, {@code ix_shrinkages_pending},
 * {@code ix_stock_adjustments_store_status}, {@code uq_inventory_counts_open_scope},
 * {@code ix_cash_sessions_store_date}).
 */
@Repository
public class DashboardJdbcRepository {

    private final JdbcClient jdbc;

    public DashboardJdbcRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Cuenta cuántos de los locales solicitados existen, están activos y son accesibles para el
     * usuario (todos si {@code allStoreAccess} es {@code true}, o presentes en {@code user_stores}).
     */
    public long countAccessibleActiveStores(List<Long> storeIds, Long userId, boolean allStoreAccess) {
        if (storeIds == null || storeIds.isEmpty()) {
            return 0L;
        }
        Long count;
        if (allStoreAccess) {
            count = jdbc.sql("""
                            SELECT COUNT(*)
                            FROM stores
                            WHERE id IN (:storeIds)
                              AND active = true
                            """)
                    .param("storeIds", storeIds)
                    .query(Long.class)
                    .single();
        } else {
            count = jdbc.sql("""
                            SELECT COUNT(*)
                            FROM stores s
                            JOIN user_stores us ON us.store_id = s.id
                            WHERE s.id IN (:storeIds)
                              AND s.active = true
                              AND us.user_id = :userId
                            """)
                    .param("storeIds", storeIds)
                    .param("userId", userId)
                    .query(Long.class)
                    .single();
        }
        return count != null ? count : 0L;
    }

    /**
     * Obtiene las ventas netas de devoluciones y conteo de transacciones por fecha de negocio y
     * hora local para {@code targetDate} y {@code previousDate}.
     *
     * <p>Las ventas {@code VOIDED} se excluyen y solo se descuentan devoluciones {@code RETURN}
     * (atribuidas a la {@code business_date} y hora de la venta original, coherente con
     * {@code DailySalesAggregateService}, evitando doble descuento de anulaciones y horas negativas).
     */
    public List<HourlySalesAggregateRow> findHourlySalesForDates(
            List<Long> storeIds,
            LocalDate targetDate,
            LocalDate previousDate,
            String zoneId) {
        return jdbc.sql("""
                        WITH returns_by_sale AS (
                            SELECT sr.sale_id,
                                   SUM(sr.refund_total) AS refund_total
                            FROM sale_returns sr
                            JOIN sales s ON s.id = sr.sale_id
                            WHERE s.store_id IN (:storeIds)
                              AND s.business_date IN (:targetDate, :previousDate)
                              AND s.status <> 'VOIDED'
                              AND sr.return_type = 'RETURN'
                            GROUP BY sr.sale_id
                        )
                        SELECT s.business_date,
                               EXTRACT(HOUR FROM (s.sold_at AT TIME ZONE :zoneId))::int AS sale_hour,
                               COUNT(*) AS transactions,
                               COALESCE(SUM(s.total_payable - COALESCE(r.refund_total, 0)), 0) AS net_total
                        FROM sales s
                        LEFT JOIN returns_by_sale r ON r.sale_id = s.id
                        WHERE s.store_id IN (:storeIds)
                          AND s.business_date IN (:targetDate, :previousDate)
                          AND s.status <> 'VOIDED'
                        GROUP BY s.business_date, sale_hour
                        ORDER BY s.business_date, sale_hour
                        """)
                .param("storeIds", storeIds)
                .param("targetDate", targetDate)
                .param("previousDate", previousDate)
                .param("zoneId", zoneId)
                .query((rs, rowNum) -> new HourlySalesAggregateRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getInt("sale_hour"),
                        rs.getLong("transactions"),
                        rs.getBigDecimal("net_total")))
                .list();
    }

    /**
     * Top 10 productos más vendidos del día (por monto neto de línea descendente, desempate por
     * cantidad neta descendente y luego {@code product_id} ascendente), excluyendo ventas
     * {@code VOIDED} y descontando devoluciones {@code RETURN} vinculadas a esas líneas.
     */
    public List<TopProductRow> findTopProducts(List<Long> storeIds, LocalDate targetDate) {
        return jdbc.sql("""
                        WITH sold_by_product AS (
                            SELECT sl.product_id,
                                   MAX(sl.product_name) AS snapshot_name,
                                   SUM(sl.quantity) AS gross_qty,
                                   SUM(sl.line_total_gross) AS gross_total
                            FROM sale_lines sl
                            JOIN sales s ON s.id = sl.sale_id
                            WHERE sl.store_id IN (:storeIds)
                              AND sl.business_date = :targetDate
                              AND s.store_id IN (:storeIds)
                              AND s.business_date = :targetDate
                              AND s.status <> 'VOIDED'
                            GROUP BY sl.product_id
                        ),
                        returned_by_product AS (
                            SELECT sl.product_id,
                                   SUM(srl.quantity) AS returned_qty,
                                   SUM(srl.refund_amount) AS returned_total
                            FROM sale_return_lines srl
                            JOIN sale_returns sr ON sr.id = srl.sale_return_id
                            JOIN sale_lines sl ON sl.id = srl.sale_line_id
                            JOIN sales s ON s.id = sl.sale_id
                            WHERE sl.store_id IN (:storeIds)
                              AND sl.business_date = :targetDate
                              AND s.store_id IN (:storeIds)
                              AND s.business_date = :targetDate
                              AND s.status <> 'VOIDED'
                              AND sr.return_type = 'RETURN'
                            GROUP BY sl.product_id
                        )
                        SELECT sp.product_id,
                               COALESCE(p.name, sp.snapshot_name) AS name,
                               (sp.gross_qty - COALESCE(rp.returned_qty, 0)) AS qty,
                               GREATEST(sp.gross_total - COALESCE(rp.returned_total, 0), 0) AS total
                        FROM sold_by_product sp
                        LEFT JOIN returned_by_product rp ON rp.product_id = sp.product_id
                        LEFT JOIN products p ON p.id = sp.product_id
                        WHERE (sp.gross_qty - COALESCE(rp.returned_qty, 0)) > 0
                        ORDER BY total DESC, qty DESC, sp.product_id ASC
                        LIMIT 10
                        """)
                .param("storeIds", storeIds)
                .param("targetDate", targetDate)
                .query((rs, rowNum) -> new TopProductRow(
                        rs.getLong("product_id"),
                        rs.getString("name"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("total")))
                .list();
    }

    /**
     * Distribución neta por medio de pago del día: pagos {@code APPROVED} de ventas no
     * {@code VOIDED} menos reembolsos de devoluciones {@code RETURN} asociadas a ventas de esa fecha.
     */
    public List<PaymentMixRow> findPaymentMix(List<Long> storeIds, LocalDate targetDate) {
        return jdbc.sql("""
                        WITH paid AS (
                            SELECT sp.method,
                                   SUM(sp.amount) AS paid_amount
                            FROM sale_payments sp
                            JOIN sales s ON s.id = sp.sale_id
                            WHERE s.store_id IN (:storeIds)
                              AND s.business_date = :targetDate
                              AND s.status <> 'VOIDED'
                              AND sp.status = 'APPROVED'
                            GROUP BY sp.method
                        ),
                        refunded AS (
                            SELECT srr.method,
                                   SUM(srr.amount) AS refund_amount
                            FROM sale_return_refunds srr
                            JOIN sale_returns sr ON sr.id = srr.sale_return_id
                            JOIN sales s ON s.id = sr.sale_id
                            WHERE s.store_id IN (:storeIds)
                              AND s.business_date = :targetDate
                              AND s.status <> 'VOIDED'
                              AND sr.return_type = 'RETURN'
                            GROUP BY srr.method
                        )
                        SELECT COALESCE(p.method, r.method) AS method,
                               (COALESCE(p.paid_amount, 0) - COALESCE(r.refund_amount, 0)) AS total
                        FROM paid p
                        FULL OUTER JOIN refunded r ON r.method = p.method
                        WHERE (COALESCE(p.paid_amount, 0) - COALESCE(r.refund_amount, 0)) > 0
                        ORDER BY total DESC, method ASC
                        """)
                .param("storeIds", storeIds)
                .param("targetDate", targetDate)
                .query((rs, rowNum) -> new PaymentMixRow(
                        rs.getString("method"),
                        rs.getBigDecimal("total")))
                .list();
    }

    /**
     * Contadores de stock bajo mínimo ({@code min_qty IS NOT NULL AND quantity <= min_qty}) y en
     * cero ({@code quantity = 0}), coherente con {@code StockQueryService.getAlerts}.
     */
    public StockAlertsRow findStockAlerts(List<Long> storeIds) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN min_qty IS NOT NULL AND quantity <= min_qty THEN 1 ELSE 0 END), 0) AS below_min,
                               COALESCE(SUM(CASE WHEN quantity = 0 THEN 1 ELSE 0 END), 0) AS zero_stock
                        FROM stock
                        WHERE store_id IN (:storeIds)
                        """)
                .param("storeIds", storeIds)
                .query((rs, rowNum) -> new StockAlertsRow(
                        rs.getLong("below_min"),
                        rs.getLong("zero_stock")))
                .single();
    }

    /**
     * Cuenta los lotes con saldo positivo vencidos o por vencer dentro de la ventana
     * {@code alerts.expiry.days_ahead} (precedencia {@code STORE} sobre {@code GLOBAL}, default 15).
     */
    public long countExpiringLots(List<Long> storeIds, LocalDate today) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM lots l
                        LEFT JOIN settings st
                          ON st.key = 'alerts.expiry.days_ahead'
                         AND st.scope = 'STORE'
                         AND st.store_id = l.store_id
                        LEFT JOIN settings gl
                          ON gl.key = 'alerts.expiry.days_ahead'
                         AND gl.scope = 'GLOBAL'
                         AND gl.store_id IS NULL
                        WHERE l.store_id IN (:storeIds)
                          AND l.quantity > 0
                          AND l.expiry_date IS NOT NULL
                          AND l.expiry_date <= (CAST(:today AS date)
                              + COALESCE(((st.value #>> '{}')::numeric)::int, ((gl.value #>> '{}')::numeric)::int, 15))
                        """)
                .param("storeIds", storeIds)
                .param("today", today)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /** Cuenta las mermas pendientes de aprobación ({@code status = 'PENDING_APPROVAL'}). */
    public long countPendingShrinkages(List<Long> storeIds) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM shrinkages
                        WHERE store_id IN (:storeIds)
                          AND status = 'PENDING_APPROVAL'
                        """)
                .param("storeIds", storeIds)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /** Cuenta los ajustes manuales de stock pendientes de aprobación ({@code status = 'PENDING_APPROVAL'}). */
    public long countPendingAdjustments(List<Long> storeIds) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM stock_adjustments
                        WHERE store_id IN (:storeIds)
                          AND status = 'PENDING_APPROVAL'
                        """)
                .param("storeIds", storeIds)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /**
     * Cuenta las tomas físicas de inventario enviadas a revisión y pendientes de aprobación
     * ({@code status = 'SUBMITTED'}).
     */
    public long countPendingCounts(List<Long> storeIds) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM inventory_counts
                        WHERE store_id IN (:storeIds)
                          AND status = 'SUBMITTED'
                        """)
                .param("storeIds", storeIds)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /**
     * Cuenta los turnos cerrados cuya diferencia absoluta excede {@code cash.difference_tolerance}
     * (precedencia {@code STORE} sobre {@code GLOBAL}, default 500) y aún no han sido aprobados
     * (RN-23).
     */
    public long countPendingCashDifferences(List<Long> storeIds) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM cash_sessions cs
                        LEFT JOIN settings st
                          ON st.key = 'cash.difference_tolerance'
                         AND st.scope = 'STORE'
                         AND st.store_id = cs.store_id
                        LEFT JOIN settings gl
                          ON gl.key = 'cash.difference_tolerance'
                         AND gl.scope = 'GLOBAL'
                         AND gl.store_id IS NULL
                        WHERE cs.store_id IN (:storeIds)
                          AND cs.status = 'CLOSED'
                          AND cs.difference_approved_by IS NULL
                          AND cs.difference_total IS NOT NULL
                          AND ABS(cs.difference_total) > COALESCE(
                              (st.value #>> '{}')::numeric,
                              (gl.value #>> '{}')::numeric,
                              500)
                        """)
                .param("storeIds", storeIds)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /** Proyección horaria de ventas netas por fecha de negocio. */
    public record HourlySalesAggregateRow(
            LocalDate businessDate,
            int hour,
            long transactions,
            BigDecimal netTotal
    ) {
    }

    /** Proyección de producto dentro del top 10 de ventas del día. */
    public record TopProductRow(
            Long productId,
            String name,
            BigDecimal qty,
            BigDecimal total
    ) {
    }

    /** Proyección de recaudación neta por medio de pago. */
    public record PaymentMixRow(
            String method,
            BigDecimal total
    ) {
    }

    /** Proyección de contadores de alertas sobre {@code stock}. */
    public record StockAlertsRow(
            long belowMin,
            long zeroStock
    ) {
    }
}
