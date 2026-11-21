package cl.minimarket.pos.reporting.purchasing.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Repositorio JDBC de solo lectura para el reporte de compras por proveedor ({@code ADR-11},
 * {@code HU-REP-08}).
 *
 * <p>Combina las órdenes de compra válidas ({@code SENT}, {@code PARTIALLY_RECEIVED},
 * {@code RECEIVED}) y las recepciones confirmadas ({@code CONFIRMED}) del período por proveedor y
 * locales solicitados, sin inflar conteos por joins 1:N.
 */
@Repository
public class PurchasingReportRepository {

    private final JdbcClient jdbc;

    public PurchasingReportRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Proyección cruda por proveedor para calcular métricas y totales ponderados en el servicio. */
    public record SupplierPurchaseAggregate(
            Long supplierId,
            String name,
            long orders,
            long receipts,
            BigDecimal netTotal,
            BigDecimal qtyOrdered,
            BigDecimal qtyReceived,
            BigDecimal leadTimeDaysSum,
            long leadTimeSamples
    ) {
    }

    /**
     * Consulta los agregados de órdenes y recepciones por proveedor dentro de
     * {@code [fromInstant, toExclusive)}.
     *
     * @param fromInstant instante UTC inclusivo de inicio del rango
     * @param toExclusive instante UTC exclusivo de fin del rango
     * @param storeIds    lista no vacía de identificadores de local
     * @return agregados ordenados por neto recibido descendente
     */
    public List<SupplierPurchaseAggregate> findSupplierAggregates(
            Instant fromInstant,
            Instant toExclusive,
            List<Long> storeIds) {
        Objects.requireNonNull(fromInstant, "fromInstant");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(storeIds, "storeIds");
        if (storeIds.isEmpty()) {
            return List.of();
        }

        OffsetDateTime fromUtc = OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC);
        OffsetDateTime toUtc = OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC);

        return jdbc.sql("""
                        WITH po_lines_agg AS (
                            SELECT po.id AS po_id,
                                   po.supplier_id,
                                   COALESCE(SUM(pol.qty_ordered), 0) AS qty_ordered,
                                   COALESCE(SUM(pol.qty_received), 0) AS qty_received
                            FROM purchase_orders po
                            LEFT JOIN purchase_order_lines pol ON pol.purchase_order_id = po.id
                            WHERE po.store_id IN (:storeIds)
                              AND po.status IN ('SENT', 'PARTIALLY_RECEIVED', 'RECEIVED')
                              AND COALESCE(po.sent_at, po.created_at) >= :fromUtc
                              AND COALESCE(po.sent_at, po.created_at) < :toUtc
                            GROUP BY po.id, po.supplier_id
                        ),
                        po_by_supplier AS (
                            SELECT supplier_id,
                                   COUNT(*) AS orders_count,
                                   COALESCE(SUM(qty_ordered), 0) AS qty_ordered,
                                   COALESCE(SUM(qty_received), 0) AS qty_received
                            FROM po_lines_agg
                            GROUP BY supplier_id
                        ),
                        gr_by_supplier AS (
                            SELECT gr.supplier_id,
                                   COUNT(*) AS receipts_count,
                                   COALESCE(SUM(gr.net_total), 0) AS net_total,
                                   SUM(CASE
                                           WHEN po.sent_at IS NOT NULL AND gr.received_at IS NOT NULL
                                           THEN GREATEST(EXTRACT(EPOCH FROM (gr.received_at - po.sent_at))::numeric, 0) / 86400.0
                                           ELSE NULL
                                       END) AS lead_time_days_sum,
                                   COUNT(CASE
                                             WHEN po.sent_at IS NOT NULL AND gr.received_at IS NOT NULL
                                             THEN 1
                                             ELSE NULL
                                         END) AS lead_time_samples
                            FROM goods_receipts gr
                            LEFT JOIN purchase_orders po ON po.id = gr.purchase_order_id
                            WHERE gr.store_id IN (:storeIds)
                              AND gr.status = 'CONFIRMED'
                              AND gr.received_at >= :fromUtc
                              AND gr.received_at < :toUtc
                            GROUP BY gr.supplier_id
                        )
                        SELECT s.id AS supplier_id,
                               s.legal_name AS supplier_name,
                               COALESCE(po.orders_count, 0) AS orders_count,
                               COALESCE(gr.receipts_count, 0) AS receipts_count,
                               COALESCE(gr.net_total, 0) AS net_total,
                               COALESCE(po.qty_ordered, 0) AS qty_ordered,
                               COALESCE(po.qty_received, 0) AS qty_received,
                               gr.lead_time_days_sum,
                               COALESCE(gr.lead_time_samples, 0) AS lead_time_samples
                        FROM suppliers s
                        LEFT JOIN po_by_supplier po ON po.supplier_id = s.id
                        LEFT JOIN gr_by_supplier gr ON gr.supplier_id = s.id
                        WHERE po.supplier_id IS NOT NULL OR gr.supplier_id IS NOT NULL
                        ORDER BY net_total DESC, orders_count DESC, supplier_name ASC, s.id ASC
                        """)
                .param("fromUtc", fromUtc)
                .param("toUtc", toUtc)
                .param("storeIds", storeIds)
                .query((rs, rowNum) -> new SupplierPurchaseAggregate(
                        rs.getLong("supplier_id"),
                        rs.getString("supplier_name"),
                        rs.getLong("orders_count"),
                        rs.getLong("receipts_count"),
                        rs.getBigDecimal("net_total"),
                        rs.getBigDecimal("qty_ordered"),
                        rs.getBigDecimal("qty_received"),
                        rs.getBigDecimal("lead_time_days_sum"),
                        rs.getLong("lead_time_samples")))
                .list();
    }
}
