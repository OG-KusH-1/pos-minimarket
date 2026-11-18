package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.inventory.LotAllocation;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persiste el desglose de lotes consumidos por cada línea de venta en {@code sale_lot_allocations}
 * a partir de las asignaciones FEFO devueltas por {@code StockApi.apply(...)}.
 *
 * <p>Cuando una venta incluye múltiples renglones que consumen el mismo {@code stock_product_id}
 * (p. ej., producto base + pack), {@code StockService} consolida el total por producto y devuelve
 * la lista FEFO conjunta; este componente reparte secuencialmente esas asignaciones entre las
 * {@link SaleLine} correspondientes respetando {@code stock_quantity} exacto por línea.</p>
 */
@Service
public class SaleLotAllocationWriter {

    private final JdbcClient jdbc;

    public SaleLotAllocationWriter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Registra en {@code sale_lot_allocations} las asignaciones de lotes correspondientes a las
     * líneas persistidas de una venta.
     *
     * @param persistedLines líneas de venta ya guardadas (con {@code id} generado)
     * @param allocationsByStockProduct asignaciones FEFO devueltas por {@code StockApi}, agrupadas por
     *     {@code stock_product_id}
     * @param createdAt instante de creación de la venta
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void persistAllocations(
            List<SaleLine> persistedLines,
            Map<Long, List<LotAllocation>> allocationsByStockProduct,
            Instant createdAt) {
        Objects.requireNonNull(persistedLines, "persistedLines");
        Objects.requireNonNull(allocationsByStockProduct, "allocationsByStockProduct");
        Objects.requireNonNull(createdAt, "createdAt");

        if (persistedLines.isEmpty() || allocationsByStockProduct.isEmpty()) {
            return;
        }

        Map<Long, Deque<RemainingAllocation>> queues = new HashMap<>(allocationsByStockProduct.size());
        for (Map.Entry<Long, List<LotAllocation>> entry : allocationsByStockProduct.entrySet()) {
            List<LotAllocation> allocations = entry.getValue();
            if (allocations == null || allocations.isEmpty()) {
                continue;
            }
            Deque<RemainingAllocation> queue = new ArrayDeque<>(allocations.size());
            for (LotAllocation allocation : allocations) {
                BigDecimal qty = Rounding.quantity(allocation.quantity());
                if (qty.signum() > 0) {
                    queue.addLast(new RemainingAllocation(allocation.lotId(), qty));
                }
            }
            if (!queue.isEmpty()) {
                queues.put(entry.getKey(), queue);
            }
        }

        if (queues.isEmpty()) {
            return;
        }

        OffsetDateTime createdAtUtc = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        for (SaleLine line : persistedLines) {
            Deque<RemainingAllocation> queue = queues.get(line.getStockProductId());
            if (queue == null || queue.isEmpty()) {
                continue;
            }

            BigDecimal remainingForLine = Rounding.quantity(line.getStockQuantity());
            while (remainingForLine.signum() > 0 && !queue.isEmpty()) {
                RemainingAllocation head = queue.peekFirst();
                if (head.remaining.signum() <= 0) {
                    queue.pollFirst();
                    continue;
                }
                BigDecimal take = Rounding.quantity(remainingForLine.min(head.remaining));
                insertAllocation(line.getId(), head.lotId, take, createdAtUtc);

                head.remaining = Rounding.quantity(head.remaining.subtract(take));
                remainingForLine = Rounding.quantity(remainingForLine.subtract(take));
                if (head.remaining.signum() <= 0) {
                    queue.pollFirst();
                }
            }

            if (remainingForLine.signum() > 0) {
                throw new IllegalStateException(
                        "Asignaciones de lote insuficientes para sale_line_id=" + line.getId()
                                + " (stock_product_id=" + line.getStockProductId()
                                + ", faltante=" + remainingForLine + ")");
            }
        }
    }

    private void insertAllocation(
            Long saleLineId, Long lotId, BigDecimal quantity, OffsetDateTime createdAt) {
        jdbc.sql("""
                INSERT INTO sale_lot_allocations (sale_line_id, lot_id, quantity, created_at)
                VALUES (:saleLineId, :lotId, :quantity, :createdAt)
                """)
                .param("saleLineId", saleLineId)
                .param("lotId", lotId)
                .param("quantity", quantity)
                .param("createdAt", createdAt)
                .update();
    }

    private static final class RemainingAllocation {
        private final Long lotId;
        private BigDecimal remaining;

        private RemainingAllocation(Long lotId, BigDecimal remaining) {
            this.lotId = lotId;
            this.remaining = remaining;
        }
    }
}
