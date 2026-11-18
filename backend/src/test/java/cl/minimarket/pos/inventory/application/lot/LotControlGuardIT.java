package cl.minimarket.pos.inventory.application.lot;

import cl.minimarket.pos.catalog.LotControlGuard;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de {@link LotControlGuardImpl}, {@link LotQueryService} y
 * {@link LotUpdateService}: creación de {@code SIN-LOTE} al activar, bloqueo {@code ENTITY_IN_USE}
 * al desactivar con saldos vigentes, sugerencia FEFO, alertas de vencimiento y edición auditada.
 */
class LotControlGuardIT extends AbstractIntegrationIT {

    @Autowired
    LotControlGuard lotControlGuard;

    @Autowired
    StockApi stockApi;

    @Autowired
    LotQueryService lotQueryService;

    @Autowired
    LotUpdateService lotUpdateService;

    @Autowired
    BusinessClock clock;

    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private long userId;
    private long storeA;
    private long storeB;
    private long storeC;
    private long categoryId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        userId = TestData.insertUser(ownerJdbc());
        storeA = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        storeB = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        storeC = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        categoryId = insertCategory();
    }

    @Test
    void activatingCreatesSinLotePerStoreWithStock_andDeactivatingRequiresZeroBalance() {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("700.0000"), false);
        insertStockRow(storeA, productId, new BigDecimal("10.000"));
        insertStockRow(storeB, productId, new BigDecimal("4.000"));
        insertStockRow(storeC, productId, BigDecimal.ZERO);

        // Activar lot_controlled con stock existente -> crea SIN-LOTE en storeA (10) y storeB (4).
        tx.executeWithoutResult(status -> lotControlGuard.apply(productId, true));

        assertThat(isLotControlled(productId)).isTrue();
        assertThat(countLots(storeA, productId)).isEqualTo(1);
        assertThat(countLots(storeB, productId)).isEqualTo(1);
        assertThat(countLots(storeC, productId)).isZero();

        Long sinLoteA = findSinLoteId(storeA, productId);
        assertThat(lotQty(sinLoteA)).isEqualByComparingTo("10.000");
        assertThat(lotQty(findSinLoteId(storeB, productId))).isEqualByComparingTo("4.000");

        // Intentar desactivar con saldo positivo -> 409 ENTITY_IN_USE.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> lotControlGuard.apply(productId, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.ENTITY_IN_USE);
        assertThat(isLotControlled(productId)).isTrue();

        // Ingresar lote con vencimiento en storeA y consumir 5 -> primero lote fechado (3), luego SIN-LOTE (2).
        LocalDate exp = clock.businessDate().plusDays(10);
        StockMovementResult datedIn = tx.execute(status -> stockApi.apply(new StockCommand(
                storeA,
                StockMovementType.PURCHASE_RECEIPT,
                StockSourceType.GOODS_RECEIPT,
                10L,
                userId,
                "Ingreso fechado",
                List.of(new StockLine(productId, new BigDecimal("3"), new BigDecimal("700"), null,
                        null, "L-FECHADO", exp))))).getFirst();
        long datedLotId = datedIn.allocations().getFirst().lotId();

        // Sugerencia FEFO y alertas de vencimiento deben reflejar el lote fechado primero.
        LotQueryService.FefoSuggestion fefoSuggestion =
                lotQueryService.suggestFefo(storeA, productId, new BigDecimal("5"));
        assertThat(fefoSuggestion.shortage()).isEqualByComparingTo("0.000");
        assertThat(fefoSuggestion.allocations()).hasSize(2);
        assertThat(fefoSuggestion.allocations().get(0).lotId()).isEqualTo(datedLotId);
        assertThat(fefoSuggestion.allocations().get(0).suggestedQuantity()).isEqualByComparingTo("3.000");
        assertThat(fefoSuggestion.allocations().get(1).lotId()).isEqualTo(sinLoteA);
        assertThat(fefoSuggestion.allocations().get(1).suggestedQuantity()).isEqualByComparingTo("2.000");

        // Sugerencia FEFO parcial cuando lo solicitado supera el saldo (16 > 13) -> sin STOCK_INSUFFICIENT.
        LotQueryService.FefoSuggestion partialSuggestion =
                lotQueryService.suggestFefo(storeA, productId, new BigDecimal("16"));
        assertThat(partialSuggestion.allocations()).hasSize(2);
        assertThat(partialSuggestion.allocations().get(0).suggestedQuantity()).isEqualByComparingTo("3.000");
        assertThat(partialSuggestion.allocations().get(1).suggestedQuantity()).isEqualByComparingTo("10.000");
        assertThat(partialSuggestion.shortage()).isEqualByComparingTo("3.000");

        Page<LotQueryService.LotExpiryAlertView> alertsWithCost =
                lotQueryService.findExpiryAlerts(storeA, 15, true, PageRequest.of(0, 20));
        assertThat(alertsWithCost.getContent())
                .anySatisfy(alert -> {
                    assertThat(alert.id()).isEqualTo(datedLotId);
                    assertThat(alert.lotNumber()).isEqualTo("L-FECHADO");
                    assertThat(alert.valuation()).isEqualByComparingTo("2100.00");
                });

        Page<LotQueryService.LotExpiryAlertView> alertsWithoutCost =
                lotQueryService.findExpiryAlerts(storeA, 15, false, PageRequest.of(0, 20));
        assertThat(alertsWithoutCost.getContent())
                .anySatisfy(alert -> {
                    assertThat(alert.id()).isEqualTo(datedLotId);
                    assertThat(alert.valuation()).isNull();
                });

        StockMovementResult saleOut = tx.execute(status -> stockApi.apply(new StockCommand(
                storeA,
                StockMovementType.SALE,
                StockSourceType.SALE,
                11L,
                userId,
                "Consumo FEFO con SIN-LOTE",
                List.of(StockLine.of(productId, new BigDecimal("5")))))).getFirst();
        assertThat(saleOut.allocations()).containsExactly(
                new LotAllocation(datedLotId, new BigDecimal("3.000")),
                new LotAllocation(sinLoteA, new BigDecimal("2.000")));

        // Agotar todo el saldo en ambos locales.
        tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                storeA, StockMovementType.ADJUSTMENT_OUT, StockSourceType.STOCK_ADJUSTMENT,
                12L, userId, "Vaciar A", List.of(StockLine.of(productId, new BigDecimal("8"))))));
        tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                storeB, StockMovementType.ADJUSTMENT_OUT, StockSourceType.STOCK_ADJUSTMENT,
                13L, userId, "Vaciar B", List.of(StockLine.of(productId, new BigDecimal("4"))))));

        // Ahora sí permite desactivar.
        tx.executeWithoutResult(status -> lotControlGuard.apply(productId, false));
        assertThat(isLotControlled(productId)).isFalse();
    }

    @Test
    void lotUpdateValidatesOptimisticLockDuplicatesAndAudits() {
        long productId = insertProduct("UNIT", "UNIT", new BigDecimal("400.0000"), true);
        insertStockRow(storeA, productId, BigDecimal.ZERO);

        LocalDate today = clock.businessDate();
        LocalDate exp1 = today.plusDays(5);
        LocalDate exp2 = today.plusDays(10);

        long lot1 = tx.execute(status -> stockApi.apply(new StockCommand(
                storeA, StockMovementType.PURCHASE_RECEIPT, StockSourceType.GOODS_RECEIPT, 50L, userId,
                null, List.of(new StockLine(productId, new BigDecimal("5"), null, null, null, "L-ONE", exp1)))))
                .getFirst().allocations().getFirst().lotId();
        tx.execute(status -> stockApi.apply(new StockCommand(
                storeA, StockMovementType.PURCHASE_RECEIPT, StockSourceType.GOODS_RECEIPT, 51L, userId,
                null, List.of(new StockLine(productId, new BigDecimal("2"), null, null, null, "L-TWO", exp2)))));

        // Paginación SQL real de alertas de vencimiento (size = 1 sobre 2 lotes)
        Page<LotQueryService.LotExpiryAlertView> alertsPage0 =
                lotQueryService.findExpiryAlerts(storeA, 15, true, PageRequest.of(0, 1));
        Page<LotQueryService.LotExpiryAlertView> alertsPage1 =
                lotQueryService.findExpiryAlerts(storeA, 15, true, PageRequest.of(1, 1));
        assertThat(alertsPage0.getTotalElements()).isEqualTo(2);
        assertThat(alertsPage0.getTotalPages()).isEqualTo(2);
        assertThat(alertsPage0.getContent()).hasSize(1);
        assertThat(alertsPage0.getContent().getFirst().id()).isEqualTo(lot1);
        assertThat(alertsPage0.getContent().getFirst().valuation()).isEqualByComparingTo("2000.00");
        assertThat(alertsPage1.getContent()).hasSize(1);
        assertThat(alertsPage1.getContent().getFirst().lotNumber()).isEqualTo("L-TWO");

        Page<LotQueryService.LotView> page = lotQueryService.findLots(
                storeA, productId, "ACTIVE", null, "L-ONE", PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        long version = page.getContent().getFirst().version();

        // Otro local -> 404 NOT_FOUND dentro del bloqueo FOR UPDATE
        assertThatThrownBy(() -> lotUpdateService.updateLot(
                storeB, lot1, "L-ONE-CORR", exp1, "Motivo válido", version))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        // Motivo inválido (< 3 caracteres) -> 400 VALIDATION_ERROR
        assertThatThrownBy(() -> lotUpdateService.updateLot(
                storeA, lot1, "L-ONE-CORR", exp1, "ab", version))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        // Duplicar clave de L-TWO -> 409 DUPLICATED_VALUE
        assertThatThrownBy(() -> lotUpdateService.updateLot(
                storeA, lot1, "L-TWO", exp2, "Corrección de duplicado", version))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.DUPLICATED_VALUE);

        // Versión incorrecta -> 409 CONCURRENT_MODIFICATION
        assertThatThrownBy(() -> lotUpdateService.updateLot(
                storeA, lot1, "L-ONE-EDIT", exp1, "Corrección con versión obsoleta", version + 9))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        // Edición válida + auditoría LOT_UPDATED con reason
        String reason = "Error de digitación en etiqueta del proveedor";
        LotQueryService.LotView updated = lotUpdateService.updateLot(
                storeA, lot1, "L-ONE-CORR", exp1.plusDays(5), reason, version);
        assertThat(updated.lotNumber()).isEqualTo("L-ONE-CORR");
        assertThat(updated.expiryDate()).isEqualTo(exp1.plusDays(5));
        assertThat(updated.version()).isEqualTo(version + 1);

        String auditReason = ownerJdbc().sql("""
                SELECT reason
                FROM audit_log
                WHERE action = 'LOT_UPDATED' AND entity_type = 'lot' AND entity_id = :id
                """)
                .param("id", Long.toString(lot1))
                .query(String.class)
                .single();
        assertThat(auditReason).isEqualTo(reason);
    }

    private boolean isLotControlled(long productId) {
        return ownerJdbc().sql("SELECT lot_controlled FROM products WHERE id = :id")
                .param("id", productId).query(Boolean.class).single();
    }

    private int countLots(long storeId, long productId) {
        return ownerJdbc().sql("SELECT COUNT(*) FROM lots WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", productId).query(Integer.class).single();
    }

    private long findSinLoteId(long storeId, long productId) {
        return ownerJdbc().sql("""
                SELECT id FROM lots
                WHERE store_id = :s AND product_id = :p AND lot_number = 'SIN-LOTE'
                """)
                .param("s", storeId).param("p", productId).query(Long.class).single();
    }

    private BigDecimal lotQty(long lotId) {
        return ownerJdbc().sql("SELECT quantity FROM lots WHERE id = :id")
                .param("id", lotId).query(BigDecimal.class).single();
    }

    private long insertCategory() {
        String name = "Cat-" + UUID.randomUUID().toString().substring(0, 8);
        return ownerJdbc().sql("INSERT INTO categories (name, active) VALUES (:name, true) RETURNING id")
                .param("name", name).query(Long.class).single();
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
        ownerJdbc().sql("INSERT INTO stock (store_id, product_id, quantity) VALUES (:s, :p, :q)")
                .param("s", targetStoreId).param("p", productId).param("q", quantity).update();
    }
}
