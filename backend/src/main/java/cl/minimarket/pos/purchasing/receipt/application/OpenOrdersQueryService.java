package cl.minimarket.pos.purchasing.receipt.application;

import cl.minimarket.pos.purchasing.OpenOrdersQuery;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implementación de {@link OpenOrdersQuery} sobre órdenes de compra abiertas ({@code SENT} o
 * {@code PARTIALLY_RECEIVED}) del módulo {@code purchasing}.
 */
@Service
public class OpenOrdersQueryService implements OpenOrdersQuery {

    private final JdbcClient jdbc;

    public OpenOrdersQueryService(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> pendingQuantities(Long storeId) {
        Objects.requireNonNull(storeId, "storeId");
        List<Map.Entry<Long, BigDecimal>> entries = jdbc.sql("""
                SELECT pol.product_id,
                       SUM(GREATEST(pol.qty_ordered - pol.qty_received, 0)) AS pending_qty
                FROM purchase_order_lines pol
                JOIN purchase_orders po ON po.id = pol.purchase_order_id
                WHERE po.store_id = :storeId
                  AND po.status IN ('SENT', 'PARTIALLY_RECEIVED')
                GROUP BY pol.product_id
                HAVING SUM(GREATEST(pol.qty_ordered - pol.qty_received, 0)) > 0
                ORDER BY pol.product_id ASC
                """)
                .param("storeId", storeId)
                .query((rs, rowNum) -> Map.entry(
                        rs.getLong("product_id"),
                        Rounding.quantity(rs.getBigDecimal("pending_qty"))))
                .list();

        Map<Long, BigDecimal> result = new LinkedHashMap<>(entries.size());
        for (Map.Entry<Long, BigDecimal> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(result);
    }
}
