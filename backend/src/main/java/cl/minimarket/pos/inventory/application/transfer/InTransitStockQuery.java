package cl.minimarket.pos.inventory.application.transfer;

import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Servicio de consulta de stock en tránsito derivado de transferencias en estado {@link
 * TransferStatus#SENT}.
 *
 * <p>Permite a {@code StockService} calcular {@code inTransitInbound} por local y {@code
 * inTransitCompanyQuantity} por producto sin acoplar el núcleo de movimientos de stock al agregado
 * de transferencias.
 */
@Service
public class InTransitStockQuery {

    private final JdbcClient jdbc;

    public InTransitStockQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Calcula la cantidad en tránsito entrante hacia el local indicado agrupada por producto.
     *
     * <p>Considera únicamente transferencias en estado {@code SENT} cuyo {@code
     * destination_store_id} coincide con {@code storeId}.
     *
     * @param storeId identificador del local de destino.
     * @return mapa {@code productId -> cantidad en tránsito} con escala de cantidad.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> inTransitInbound(Long storeId) {
        if (storeId == null) {
            return Map.of();
        }
        String sql =
                """
                SELECT l.product_id, COALESCE(SUM(l.qty_sent), 0) AS qty
                  FROM stock_transfer_lines l
                  JOIN stock_transfers t ON t.id = l.transfer_id
                 WHERE t.destination_store_id = :storeId
                   AND t.status = 'SENT'
                 GROUP BY l.product_id
                 HAVING COALESCE(SUM(l.qty_sent), 0) > 0
                """;
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        jdbc.sql(sql)
                .param("storeId", storeId)
                .query(
                        rs -> {
                            long productId = rs.getLong("product_id");
                            BigDecimal qty = Rounding.quantity(rs.getBigDecimal("qty"));
                            result.put(productId, qty);
                        });
        return Map.copyOf(result);
    }

    /**
     * Calcula la cantidad total en tránsito a nivel de toda la empresa para un producto.
     *
     * @param productId identificador del producto.
     * @return suma de {@code qty_sent} en transferencias con estado {@code SENT}, o cero si no hay.
     */
    @Transactional(readOnly = true)
    public BigDecimal inTransitCompanyQuantity(Long productId) {
        if (productId == null) {
            return BigDecimal.ZERO;
        }
        String sql =
                """
                SELECT COALESCE(SUM(l.qty_sent), 0)
                  FROM stock_transfer_lines l
                  JOIN stock_transfers t ON t.id = l.transfer_id
                 WHERE l.product_id = :productId
                   AND t.status = 'SENT'
                """;
        BigDecimal total =
                jdbc.sql(sql).param("productId", productId).query(BigDecimal.class).single();
        return total == null ? BigDecimal.ZERO : Rounding.quantity(total);
    }
}
