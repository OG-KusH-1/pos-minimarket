package cl.minimarket.pos.inventory.application.stock;

import cl.minimarket.pos.inventory.LotAllocation;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementResult;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de manejo de lotes, asignación FEFO ({@code SELECT ... FOR UPDATE}),
 * concurrencia, compatibilidad con ajustes/tomas e invariante RN-04 ({@code SUM(lots) = stock}).
 */
class StockLotFefoIT extends AbstractIntegrationIT {

    @Autowired
    StockApi stockApi;

    @Autowired
    BusinessClock clock;

    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private long userId;
    private long storeId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        userId = TestData.insertUser(ownerJdbc());
        storeId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        categoryId = insertCategory();
    }

    @Test
    void fefoOutboundConsumesEarliestExpiryFirstAndNullsLast_maintainingInvariant() {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("500.0000"), true);
        insertStockRow(storeId, productId, BigDecimal.ZERO);

        LocalDate today = clock.businessDate();
        LocalDate june = today.plusMonths(4);
        LocalDate april = today.plusMonths(2);

        // Ingresar en orden inverso: sin vencimiento, junio, abril.
        StockMovementResult sinLoteIn = applySingle(StockMovementType.PURCHASE_RECEIPT, StockSourceType.GOODS_RECEIPT, 1L,
                new StockLine(productId, new BigDecimal("5"), new BigDecimal("480"), null, null, "SIN-LOTE", null));
        StockMovementResult juneIn = applySingle(StockMovementType.PURCHASE_RECEIPT, StockSourceType.GOODS_RECEIPT, 2L,
                new StockLine(productId, new BigDecimal("4"), new BigDecimal("500"), null, null, "L-JUN", june));
        StockMovementResult aprilIn = applySingle(StockMovementType.PURCHASE_RECEIPT, StockSourceType.GOODS_RECEIPT, 3L,
                new StockLine(productId, new BigDecimal("3"), new BigDecimal("510"), null, null, "L-ABR", april));

        long sinLoteId = sinLoteIn.allocations().getFirst().lotId();
        long juneLotId = juneIn.allocations().getFirst().lotId();
        long aprilLotId = aprilIn.allocations().getFirst().lotId();

        // Cada entrada de un único lote deja stock_movements.lot_id poblado.
        assertThat(lotIdOfMovement(aprilIn.movementId())).isEqualTo(aprilLotId);
        assertLotsMatchStock(storeId, productId);

        // Venta de 6 unidades: debe tomar 3 de abril (queda DEPLETED) y 3 de junio (queda 1 ACTIVE).
        StockMovementResult saleRes = applySingle(StockMovementType.SALE, StockSourceType.SALE, 100L,
                StockLine.of(productId, new BigDecimal("6")));

        assertThat(saleRes.balanceAfter()).isEqualByComparingTo("6.000");
        assertThat(saleRes.allocations()).containsExactly(
                new LotAllocation(aprilLotId, new BigDecimal("3.000")),
                new LotAllocation(juneLotId, new BigDecimal("3.000")));
        // Movimiento que toca > 1 lote deja stock_movements.lot_id en null.
        assertThat(lotIdOfMovement(saleRes.movementId())).isNull();

        assertThat(lotStatus(aprilLotId)).isEqualTo("DEPLETED");
        assertThat(lotQty(aprilLotId)).isEqualByComparingTo("0.000");
        assertThat(lotStatus(juneLotId)).isEqualTo("ACTIVE");
        assertThat(lotQty(juneLotId)).isEqualByComparingTo("1.000");
        assertThat(lotQty(sinLoteId)).isEqualByComparingTo("5.000");
        assertLotsMatchStock(storeId, productId);

        // Segunda salida de 3: consume el saldo de junio (1) y 2 de SIN-LOTE (NULLS LAST).
        StockMovementResult saleRes2 = applySingle(StockMovementType.SALE, StockSourceType.SALE, 101L,
                StockLine.of(productId, new BigDecimal("3")));

        assertThat(saleRes2.allocations()).containsExactly(
                new LotAllocation(juneLotId, new BigDecimal("1.000")),
                new LotAllocation(sinLoteId, new BigDecimal("2.000")));
        assertThat(lotStatus(juneLotId)).isEqualTo("DEPLETED");
        assertThat(lotQty(sinLoteId)).isEqualByComparingTo("3.000");
        assertLotsMatchStock(storeId, productId);
    }

    @Test
    void inboundSupportsDepletedPreCreatedLotId_andExplicitLotIdAdjustment() {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("1000.0000"), true);
        insertStockRow(storeId, productId, BigDecimal.ZERO);

        LocalDate exp = clock.businessDate().plusMonths(6);
        // Patrón T49: lote pre-creado en ajuste pendiente con quantity=0 y status='DEPLETED'.
        long preCreatedLotId = ownerJdbc().sql("""
                INSERT INTO lots (store_id, product_id, lot_number, expiry_date, quantity, status, received_at)
                VALUES (:s, :p, 'L-PRE', :exp, 0, 'DEPLETED', now())
                RETURNING id
                """)
                .param("s", storeId)
                .param("p", productId)
                .param("exp", exp)
                .query(Long.class)
                .single();

        StockMovementResult inRes = applySingle(StockMovementType.ADJUSTMENT_IN, StockSourceType.STOCK_ADJUSTMENT, 200L,
                new StockLine(productId, new BigDecimal("8"), new BigDecimal("1000"), null,
                        preCreatedLotId, "L-PRE", exp));

        assertThat(inRes.allocations()).containsExactly(new LotAllocation(preCreatedLotId, new BigDecimal("8.000")));
        assertThat(lotStatus(preCreatedLotId)).isEqualTo("ACTIVE");
        assertThat(lotQty(preCreatedLotId)).isEqualByComparingTo("8.000");
        assertLotsMatchStock(storeId, productId);

        // Patrón T50: ajuste negativo dirigido por lotId sin enviar lotNumber ni expiryDate.
        StockMovementResult outRes = applySingle(StockMovementType.ADJUSTMENT_OUT, StockSourceType.INVENTORY_COUNT, 201L,
                new StockLine(productId, new BigDecimal("3"), null, null, preCreatedLotId, null, null));

        assertThat(outRes.allocations()).containsExactly(new LotAllocation(preCreatedLotId, new BigDecimal("3.000")));
        assertThat(lotQty(preCreatedLotId)).isEqualByComparingTo("5.000");
        assertLotsMatchStock(storeId, productId);
    }

    @Test
    void validatesLotRequired_nonLotRejection_andRollsBackOnInsufficient() {
        long lotProductId = insertProduct("UNIT", "UNIT", new BigDecimal("100"), true);
        long plainProductId = insertProduct("UNIT", "UNIT", new BigDecimal("100"), false);
        insertStockRow(storeId, lotProductId, BigDecimal.ZERO);
        insertStockRow(storeId, plainProductId, BigDecimal.ZERO);

        // Entrada en producto con lot_controlled = true sin lote -> 422 LOT_REQUIRED
        assertThatThrownBy(() -> applySingle(StockMovementType.PURCHASE_RECEIPT, StockSourceType.GOODS_RECEIPT, 300L,
                StockLine.of(lotProductId, new BigDecimal("5"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOT_REQUIRED);

        // Producto sin lot_controlled con datos de lote -> 422 VALIDATION_ERROR
        assertThatThrownBy(() -> applySingle(StockMovementType.PURCHASE_RECEIPT, StockSourceType.GOODS_RECEIPT, 301L,
                new StockLine(plainProductId, new BigDecimal("5"), null, null, null, "L-X", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        // Cargar 4 unidades en lote L-1, luego intentar venta multi-línea donde la 2da línea excede saldo -> rollback completo.
        StockMovementResult inRes = applySingle(StockMovementType.INITIAL, StockSourceType.INITIAL_LOAD, 302L,
                new StockLine(lotProductId, new BigDecimal("4"), new BigDecimal("100"), null, null, "L-1",
                        clock.businessDate().plusMonths(5)));
        long lotId = inRes.allocations().getFirst().lotId();

        applySingle(StockMovementType.INITIAL, StockSourceType.INITIAL_LOAD, 303L,
                StockLine.of(plainProductId, new BigDecimal("10")));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                storeId,
                StockMovementType.SALE,
                StockSourceType.SALE,
                304L,
                userId,
                "Rollback multi-linea",
                List.of(
                        StockLine.of(lotProductId, new BigDecimal("2")),
                        StockLine.of(plainProductId, new BigDecimal("15")))))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STOCK_INSUFFICIENT);

        assertThat(lotQty(lotId)).isEqualByComparingTo("4.000");
        assertThat(stockApi.onHand(storeId, lotProductId)).isEqualByComparingTo("4.000");
        assertThat(stockApi.onHand(storeId, plainProductId)).isEqualByComparingTo("10.000");
    }

    @Test
    void twoConcurrentFefoSales_onlyOneSucceeds_andLotsEqualStock() throws Exception {
        int rounds = 10;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < rounds; round++) {
                long productId = insertProduct("UNIT", "UNIT", new BigDecimal("600.0000"), true);
                insertStockRow(storeId, productId, BigDecimal.ZERO);

                LocalDate expA = clock.businessDate().plusMonths(1);
                LocalDate expB = clock.businessDate().plusMonths(4);
                applySingle(StockMovementType.PURCHASE_RECEIPT, StockSourceType.GOODS_RECEIPT, 400L + round,
                        new StockLine(productId, new BigDecimal("2"), new BigDecimal("600"), null,
                                null, "L-A", expA));
                applySingle(StockMovementType.PURCHASE_RECEIPT, StockSourceType.GOODS_RECEIPT, 500L + round,
                        new StockLine(productId, new BigDecimal("3"), new BigDecimal("600"), null,
                                null, "L-B", expB));

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger successes = new AtomicInteger();
                AtomicInteger insufficient = new AtomicInteger();

                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    long saleId = 6000L + round * 10L + i;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                                    storeId,
                                    StockMovementType.SALE,
                                    StockSourceType.SALE,
                                    saleId,
                                    userId,
                                    "Venta concurrente FEFO",
                                    List.of(StockLine.of(productId, new BigDecimal("3"))))));
                            successes.incrementAndGet();
                        } catch (BusinessException ex) {
                            if (ex.getCode() == ErrorCode.STOCK_INSUFFICIENT) {
                                insufficient.incrementAndGet();
                            } else {
                                throw ex;
                            }
                        }
                        return null;
                    }));
                }

                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(15, TimeUnit.SECONDS);
                }

                assertThat(successes.get()).isEqualTo(1);
                assertThat(insufficient.get()).isEqualTo(1);
                assertThat(stockApi.onHand(storeId, productId)).isEqualByComparingTo("2.000");
                assertLotsMatchStock(storeId, productId);
            }
        }
    }

    private StockMovementResult applySingle(
            StockMovementType type, StockSourceType sourceType, long sourceId, StockLine line) {
        return tx.execute(status -> stockApi.apply(new StockCommand(
                storeId, type, sourceType, sourceId, userId, "Test T48", List.of(line)))).getFirst();
    }

    private void assertLotsMatchStock(long targetStoreId, long productId) {
        BigDecimal stockQty = stockApi.onHand(targetStoreId, productId);
        BigDecimal lotsSum = ownerJdbc().sql("""
                SELECT COALESCE(SUM(quantity), 0)
                FROM lots
                WHERE store_id = :s AND product_id = :p
                """)
                .param("s", targetStoreId)
                .param("p", productId)
                .query(BigDecimal.class)
                .single();
        assertThat(lotsSum).isEqualByComparingTo(stockQty);
    }

    private BigDecimal lotQty(long lotId) {
        return ownerJdbc().sql("SELECT quantity FROM lots WHERE id = :id")
                .param("id", lotId).query(BigDecimal.class).single();
    }

    private String lotStatus(long lotId) {
        return ownerJdbc().sql("SELECT status FROM lots WHERE id = :id")
                .param("id", lotId).query(String.class).single();
    }

    private Long lotIdOfMovement(long movementId) {
        return ownerJdbc().sql("SELECT lot_id FROM stock_movements WHERE id = :id")
                .param("id", movementId).query(Long.class).optional().orElse(null);
    }

    private long insertCategory() {
        String name = "Cat-" + UUID.randomUUID().toString().substring(0, 8);
        return ownerJdbc().sql("INSERT INTO categories (name, active) VALUES (:name, true) RETURNING id")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertProduct(String saleType, String uom, BigDecimal avgCost, boolean lotControlled) {
        String sku = "SKU-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return ownerJdbc().sql("""
                INSERT INTO products (
                    sku, name, category_id, sale_type, unit_of_measure,
                    price_gross, avg_cost, lot_controlled, active
                ) VALUES (
                    :sku, :name, :categoryId, :saleType, :uom,
                    1000, :avgCost, :lotControlled, true
                )
                RETURNING id
                """)
                .param("sku", sku)
                .param("name", "Producto " + sku)
                .param("categoryId", categoryId)
                .param("saleType", saleType)
                .param("uom", uom)
                .param("avgCost", avgCost)
                .param("lotControlled", lotControlled)
                .query(Long.class)
                .single();
    }

    private void insertStockRow(long targetStoreId, long productId, BigDecimal quantity) {
        ownerJdbc().sql("""
                INSERT INTO stock (store_id, product_id, quantity)
                VALUES (:storeId, :productId, :qty)
                """)
                .param("storeId", targetStoreId)
                .param("productId", productId)
                .param("qty", quantity)
                .update();
    }
}
