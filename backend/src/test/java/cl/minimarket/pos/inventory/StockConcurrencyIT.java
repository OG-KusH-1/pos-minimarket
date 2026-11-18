package cl.minimarket.pos.inventory;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración y concurrencia de {@link StockApi} (T20, ADR-13, RN-01..RN-03).
 *
 * <p>Verifica el comportamiento atómico bajo alta concurrencia, ausencia de deadlocks en pedidos
 * cruzados, rollback completo en fallos multi-línea, escritura exacta del kardex y las consultas
 * {@code onHand}, {@code companyQuantity} e {@code inTransitInbound}.
 */
class StockConcurrencyIT extends AbstractIntegrationIT {

    @Autowired
    StockApi stockApi;

    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private long userId;
    private long storeId;
    private long categoryId;

    @BeforeEach
    void setUpBaseData() {
        tx = new TransactionTemplate(txManager);
        userId = TestData.insertUser(ownerJdbc());
        storeId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        categoryId = insertCategory();
    }

    @Test
    void twoConcurrentOutboundApply_onlyOneSucceeds_andFinalStockIsTwo() throws Exception {
        int rounds = 20;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < rounds; round++) {
                long productId = insertProduct("UNIT", "UNIT", new BigDecimal("1200.0000"), false);
                insertStockRow(storeId, productId, new BigDecimal("5.000"));

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger successes = new AtomicInteger();
                AtomicInteger insufficientErrors = new AtomicInteger();

                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    long saleId = 1000L + round * 10L + i;
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
                                    "Venta concurrente",
                                    List.of(StockLine.of(productId, new BigDecimal("3"))))));
                            successes.incrementAndGet();
                        } catch (BusinessException ex) {
                            if (ex.getCode() == ErrorCode.STOCK_INSUFFICIENT) {
                                insufficientErrors.incrementAndGet();
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
                assertThat(insufficientErrors.get()).isEqualTo(1);
                assertThat(stockApi.onHand(storeId, productId)).isEqualByComparingTo("2.000");

                Integer movementCount = appJdbc().sql("""
                        SELECT COUNT(*) FROM stock_movements
                        WHERE store_id = :storeId AND product_id = :productId
                        """)
                        .param("storeId", storeId)
                        .param("productId", productId)
                        .query(Integer.class)
                        .single();
                assertThat(movementCount).isEqualTo(1);
            }
        }
    }

    @Test
    void crossedOrdersInParallel_completeWithoutDeadlock() throws Exception {
        long productA = insertProduct("UNIT", "UNIT", new BigDecimal("500.0000"), false);
        long productB = insertProduct("UNIT", "UNIT", new BigDecimal("800.0000"), false);
        insertStockRow(storeId, productA, new BigDecimal("100.000"));
        insertStockRow(storeId, productB, new BigDecimal("100.000"));

        int workers = 8;
        int roundsPerWorker = 5;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                final int workerIndex = w;
                futures.add(pool.submit((Callable<Void>) () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    for (int r = 0; r < roundsPerWorker; r++) {
                        long sourceId = 2000L + workerIndex * 100L + r;
                        List<StockLine> lines = (workerIndex % 2 == 0)
                                ? List.of(StockLine.of(productA, BigDecimal.ONE), StockLine.of(productB, BigDecimal.ONE))
                                : List.of(StockLine.of(productB, BigDecimal.ONE), StockLine.of(productA, BigDecimal.ONE));
                        tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                                storeId,
                                StockMovementType.SALE,
                                StockSourceType.SALE,
                                sourceId,
                                userId,
                                "Pedido cruzado",
                                lines)));
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        BigDecimal expected = new BigDecimal("60.000"); // 100 - (8 * 5)
        assertThat(stockApi.onHand(storeId, productA)).isEqualByComparingTo(expected);
        assertThat(stockApi.onHand(storeId, productB)).isEqualByComparingTo(expected);
    }

    @Test
    void twentyFourThreadsWithRandomOutbounds_neverNegativeAndKardexMatchesBalance() throws Exception {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("950.0000"), false);
        BigDecimal initialStock = new BigDecimal("40.000");
        insertStockRow(storeId, productId, initialStock);

        int threads = 24;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Random random = new Random(42L);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficientErrors = new AtomicInteger();
        AtomicInteger successfulQtySum = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int qty = 1 + random.nextInt(5);
                final long sourceId = 3000L + i;
                futures.add(pool.submit((Callable<Void>) () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                                storeId,
                                StockMovementType.SALE,
                                StockSourceType.SALE,
                                sourceId,
                                userId,
                                "Salida aleatoria",
                                List.of(StockLine.of(productId, BigDecimal.valueOf(qty))))));
                        successes.incrementAndGet();
                        successfulQtySum.addAndGet(qty);
                    } catch (BusinessException ex) {
                        if (ex.getCode() == ErrorCode.STOCK_INSUFFICIENT) {
                            insufficientErrors.incrementAndGet();
                        } else {
                            throw ex;
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(successes.get()).isGreaterThan(0);
        assertThat(insufficientErrors.get()).isGreaterThan(0);
        assertThat(successes.get() + insufficientErrors.get()).isEqualTo(threads);

        BigDecimal finalOnHand = stockApi.onHand(storeId, productId);
        assertThat(finalOnHand).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        BigDecimal expectedFinal = initialStock.subtract(BigDecimal.valueOf(successfulQtySum.get()));
        assertThat(finalOnHand).isEqualByComparingTo(expectedFinal);

        List<BigDecimal> balances = appJdbc().sql("""
                SELECT balance_after
                FROM stock_movements
                WHERE store_id = :storeId AND product_id = :productId
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .query(BigDecimal.class)
                .list();
        assertThat(balances).hasSize(successes.get());
        assertThat(balances).allSatisfy(b -> assertThat(b).isGreaterThanOrEqualTo(BigDecimal.ZERO));

        BigDecimal sumDeltas = appJdbc().sql("""
                SELECT COALESCE(SUM(quantity_delta), 0)
                FROM stock_movements
                WHERE store_id = :storeId AND product_id = :productId
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .query(BigDecimal.class)
                .single();

        assertThat(initialStock.add(sumDeltas)).isEqualByComparingTo(finalOnHand);
    }

    @Test
    void multiLineApplyWithOneFailure_rollsBackAllChanges() {
        long productOk = insertProduct("UNIT", "UNIT", new BigDecimal("400.0000"), false);
        long productLow = insertProduct("UNIT", "UNIT", new BigDecimal("600.0000"), false);
        insertStockRow(storeId, productOk, new BigDecimal("10.000"));
        insertStockRow(storeId, productLow, new BigDecimal("2.000"));

        StockCommand cmd = new StockCommand(
                storeId,
                StockMovementType.SALE,
                StockSourceType.SALE,
                4001L,
                userId,
                "Venta multi-línea fallida",
                List.of(
                        StockLine.of(productOk, new BigDecimal("3")),
                        StockLine.of(productLow, new BigDecimal("5"))));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> stockApi.apply(cmd)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STOCK_INSUFFICIENT);

        assertThat(stockApi.onHand(storeId, productOk)).isEqualByComparingTo("10.000");
        assertThat(stockApi.onHand(storeId, productLow)).isEqualByComparingTo("2.000");

        Integer movements = appJdbc().sql("""
                SELECT COUNT(*) FROM stock_movements
                WHERE store_id = :storeId AND product_id IN (:p1, :p2)
                """)
                .param("storeId", storeId)
                .param("p1", productOk)
                .param("p2", productLow)
                .query(Integer.class)
                .single();
        assertThat(movements).isZero();
    }

    @Test
    void apply_withoutActiveTransaction_throwsMandatoryTransactionError() {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("100.0000"), false);
        insertStockRow(storeId, productId, new BigDecimal("5.000"));

        StockCommand cmd = new StockCommand(
                storeId,
                StockMovementType.SALE,
                StockSourceType.SALE,
                5001L,
                userId,
                null,
                List.of(StockLine.of(productId, BigDecimal.ONE)));

        assertThatThrownBy(() -> stockApi.apply(cmd))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void apply_inboundAndOutbound_writesKardexWithAvgOrExplicitCost() {
        long productId = insertProduct("WEIGHT", "KG", new BigDecimal("2500.0000"), false);
        insertStockRow(storeId, productId, BigDecimal.ZERO);

        List<StockMovementResult> inbound = tx.execute(status -> stockApi.apply(new StockCommand(
                storeId,
                StockMovementType.PURCHASE_RECEIPT,
                StockSourceType.GOODS_RECEIPT,
                6001L,
                userId,
                "Recepción inicial",
                List.of(StockLine.of(productId, new BigDecimal("10.500"), new BigDecimal("2400.5000"), 11L)))));

        assertThat(inbound).hasSize(1);
        assertThat(inbound.getFirst().quantityDelta()).isEqualByComparingTo("10.500");
        assertThat(inbound.getFirst().balanceAfter()).isEqualByComparingTo("10.500");
        assertThat(inbound.getFirst().unitCost()).isEqualByComparingTo("2400.5000");
        assertThat(inbound.getFirst().allocations()).isEmpty();

        List<StockMovementResult> outbound = tx.execute(status -> stockApi.apply(new StockCommand(
                storeId,
                StockMovementType.SALE,
                StockSourceType.SALE,
                6002L,
                userId,
                "Venta granel",
                // Aunque se pase un unitCost explícito en salida, ADR-15 exige usar avg_cost vigente.
                List.of(StockLine.of(productId, new BigDecimal("0.750"), new BigDecimal("9999.0000"), null)))));

        assertThat(outbound).hasSize(1);
        assertThat(outbound.getFirst().quantityDelta()).isEqualByComparingTo("-0.750");
        assertThat(outbound.getFirst().balanceAfter()).isEqualByComparingTo("9.750");
        // Salida usa siempre el avg_cost vigente del producto (ignora unitCost recibido).
        assertThat(outbound.getFirst().unitCost()).isEqualByComparingTo("2500.0000");
    }

    @Test
    void apply_missingStockRow_throwsNotFound() {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("100.0000"), false);

        StockCommand cmd = new StockCommand(
                storeId,
                StockMovementType.ADJUSTMENT_IN,
                StockSourceType.STOCK_ADJUSTMENT,
                7001L,
                userId,
                null,
                List.of(StockLine.of(productId, BigDecimal.ONE)));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> stockApi.apply(cmd)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void apply_outboundMissingStockRow_throwsNotFound() {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("100.0000"), false);

        StockCommand cmd = new StockCommand(
                storeId,
                StockMovementType.SALE,
                StockSourceType.SALE,
                7002L,
                userId,
                null,
                List.of(StockLine.of(productId, BigDecimal.ONE)));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> stockApi.apply(cmd)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void apply_lotDataForLotControlledProduct_allocatesAndRecordsLotMovementInPhase2() {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("300.0000"), true);
        insertStockRow(storeId, productId, new BigDecimal("10.000"));

        StockCommand cmd = new StockCommand(
                storeId,
                StockMovementType.PURCHASE_RECEIPT,
                StockSourceType.GOODS_RECEIPT,
                8001L,
                userId,
                null,
                List.of(new StockLine(productId, new BigDecimal("5"), new BigDecimal("300"), null,
                        null, "LOT-2026", LocalDate.of(2027, 6, 30))));

        List<StockMovementResult> results = tx.execute(status -> stockApi.apply(cmd));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().balanceAfter()).isEqualByComparingTo("15.000");
        assertThat(results.getFirst().allocations()).hasSize(1);
        assertThat(results.getFirst().allocations().getFirst().quantity())
                .isEqualByComparingTo("5.000");
    }

    @Test
    void apply_fractionalQuantityForUnitProduct_throwsValidationError() {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("100.0000"), false);
        insertStockRow(storeId, productId, new BigDecimal("10.000"));

        StockCommand cmd = new StockCommand(
                storeId,
                StockMovementType.SALE,
                StockSourceType.SALE,
                9001L,
                userId,
                null,
                List.of(StockLine.of(productId, new BigDecimal("1.500"))));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> stockApi.apply(cmd)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void companyQuantity_sumsAcrossStores_and_inTransitInboundIsEmpty() {
        long secondStoreId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("100.0000"), false);
        insertStockRow(storeId, productId, new BigDecimal("7.000"));
        insertStockRow(secondStoreId, productId, new BigDecimal("4.000"));

        assertThat(stockApi.onHand(storeId, productId)).isEqualByComparingTo("7.000");
        assertThat(stockApi.onHand(secondStoreId, productId)).isEqualByComparingTo("4.000");
        assertThat(stockApi.companyQuantity(productId)).isEqualByComparingTo("11.000");
        assertThat(stockApi.inTransitInbound(storeId)).isEmpty();
    }

    private long insertCategory() {
        String name = "Cat-" + UUID.randomUUID().toString().substring(0, 8);
        return ownerJdbc().sql("""
                INSERT INTO categories (name, active) VALUES (:name, true) RETURNING id
                """)
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
