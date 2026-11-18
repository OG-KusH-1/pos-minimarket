package cl.minimarket.pos.inventory.application.lot;

import cl.minimarket.pos.catalog.LotControlGuard;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Implementación definitiva de {@link LotControlGuard} para habilitar o deshabilitar el control
 * por lotes ({@code products.lot_controlled}) manteniendo el invariante RN-04
 * ({@code SUM(lots.quantity) = stock.quantity} por local).
 *
 * <p><b>Reglas de negocio:</b>
 * <ul>
 *   <li>Al activar ({@code lotControlled = true}): si existen locales con stock mayor a cero sin
 *       respaldo en {@code lots}, crea o incrementa el lote comodín {@code SIN-LOTE} (con
 *       {@code expiry_date = NULL}) por la diferencia en cada local afectado (HU-CAT-09).</li>
 *   <li>Al desactivar ({@code lotControlled = false}): bloquea las filas de {@code stock} y
 *       {@code lots} del producto y rechaza con {@code 409 ENTITY_IN_USE} si existe cualquier lote o
 *       local con saldo mayor a cero.</li>
 * </ul>
 *
 * <p><b>Estrategia de bloqueos:</b> siempre bloquea {@code stock} en orden ascendente de
 * {@code store_id} antes de consultar o modificar {@code lots}, respetando la misma jerarquía de
 * locks que {@code StockService} para evitar deadlocks. Asimismo, evita inyectar
 * {@code ProductCatalogApi} o {@code StockApi} para prevenir ciclos de dependencias Spring con
 * {@code ProductService}.</p>
 */
@Service
public class LotControlGuardImpl implements LotControlGuard {

    public static final String NO_LOT_NUMBER = "SIN-LOTE";

    private static final String LOT_STATUS_ACTIVE = "ACTIVE";

    private final JdbcClient jdbc;
    private final BusinessClock clock;

    public LotControlGuardImpl(JdbcClient jdbc, BusinessClock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(Long productId, boolean lotControlled) {
        Objects.requireNonNull(productId, "productId");

        ProductCostInfo product = jdbc.sql("""
                SELECT lot_controlled, avg_cost
                FROM products
                WHERE id = :productId
                """)
                .param("productId", productId)
                .query((rs, rowNum) -> new ProductCostInfo(
                        rs.getBoolean("lot_controlled"),
                        rs.getBigDecimal("avg_cost")))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Producto no encontrado: " + productId));

        // Bloquear stock por store_id ascendente antes de inspeccionar lots (consistente con StockService).
        List<StoreStockRow> storeStocks = jdbc.sql("""
                SELECT store_id, quantity
                FROM stock
                WHERE product_id = :productId
                ORDER BY store_id ASC
                FOR UPDATE
                """)
                .param("productId", productId)
                .query((rs, rowNum) -> new StoreStockRow(
                        rs.getLong("store_id"),
                        Rounding.quantity(rs.getBigDecimal("quantity"))))
                .list();

        if (lotControlled) {
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
            Long userId = CurrentUserId.find().orElse(null);

            for (StoreStockRow storeStock : storeStocks) {
                if (storeStock.quantity().signum() <= 0) {
                    continue;
                }
                List<BigDecimal> lotQuantities = jdbc.sql("""
                        SELECT quantity
                        FROM lots
                        WHERE store_id = :storeId
                          AND product_id = :productId
                        ORDER BY id ASC
                        FOR UPDATE
                        """)
                        .param("storeId", storeStock.storeId())
                        .param("productId", productId)
                        .query(BigDecimal.class)
                        .list();

                BigDecimal existingLotsSum = lotQuantities.stream()
                        .map(Rounding::quantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal missingQty = Rounding.quantity(storeStock.quantity().subtract(existingLotsSum));
                if (missingQty.signum() > 0) {
                    upsertNoLotBalance(storeStock.storeId(), productId, missingQty,
                            Rounding.unitCost(product.avgCost()), userId, now);
                }
            }
        } else {
            List<BigDecimal> activeLotQuantities = jdbc.sql("""
                    SELECT quantity
                    FROM lots
                    WHERE product_id = :productId
                      AND quantity > 0
                    ORDER BY store_id ASC, id ASC
                    FOR UPDATE
                    """)
                    .param("productId", productId)
                    .query(BigDecimal.class)
                    .list();

            boolean hasLotBalance = activeLotQuantities.stream()
                    .anyMatch(q -> q != null && q.signum() > 0);
            boolean hasStockBalance = storeStocks.stream()
                    .anyMatch(s -> s.quantity().signum() > 0);

            if (hasLotBalance || hasStockBalance) {
                throw new BusinessException(
                        ErrorCode.ENTITY_IN_USE,
                        "No se puede desactivar el control de lotes mientras exista stock o lotes con saldo positivo");
            }
        }

        jdbc.sql("""
                UPDATE products
                SET lot_controlled = :lotControlled
                WHERE id = :productId
                """)
                .param("lotControlled", lotControlled)
                .param("productId", productId)
                .update();
    }

    private void upsertNoLotBalance(
            Long storeId,
            Long productId,
            BigDecimal quantity,
            BigDecimal unitCost,
            Long userId,
            OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO lots (
                    store_id, product_id, lot_number, expiry_date,
                    quantity, unit_cost, received_at, status,
                    created_by, updated_by, created_at, updated_at
                ) VALUES (
                    :storeId, :productId, :lotNumber, NULL,
                    :qty, :unitCost, :now, :status,
                    :userId, :userId, :now, :now
                )
                ON CONFLICT (store_id, product_id, lot_number, COALESCE(expiry_date, 'infinity'::date))
                DO UPDATE SET
                    quantity = lots.quantity + EXCLUDED.quantity,
                    unit_cost = COALESCE(lots.unit_cost, EXCLUDED.unit_cost),
                    status = :status,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at,
                    version = lots.version + 1
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .param("lotNumber", NO_LOT_NUMBER)
                .param("qty", quantity)
                .param("unitCost", unitCost, Types.NUMERIC)
                .param("now", now)
                .param("status", LOT_STATUS_ACTIVE)
                .param("userId", userId, Types.BIGINT)
                .update();
    }

    private record ProductCostInfo(boolean lotControlled, BigDecimal avgCost) {
    }

    private record StoreStockRow(Long storeId, BigDecimal quantity) {
    }
}
