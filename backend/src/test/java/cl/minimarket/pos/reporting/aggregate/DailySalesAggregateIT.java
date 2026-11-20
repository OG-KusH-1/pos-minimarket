package cl.minimarket.pos.reporting.aggregate;

import cl.minimarket.pos.reporting.SalesHistoryApi;
import cl.minimarket.pos.reporting.SalesHistoryApi.DailyDemand;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PI del agregado diario de ventas por local y producto ({@code daily_product_sales}, T78 y
 * MODELO_DATOS B.11).
 *
 * <p>Siembra un escenario propio con claves únicas (local, productos, ventas, kardex y
 * devoluciones sintéticas de junio de 2030, cuando Santiago va en hora estándar UTC−4, sin cambio
 * de hora) e invoca el servicio directamente con la fecha de corrida controlada
 * {@link #RUN_DATE}, que es lo que hace el job de las 02:00 con el reloj de negocio.
 *
 * <p>Criterio de terminado de T78: {@code qty_sold} neto de una devolución (y devolución antigua
 * rescatada por el barrido), {@code had_stockout} cuando hubo quiebre (saldo cero del kardex y
 * apertura en cero), recálculo de 3 días sin borrar lo anterior, y {@link SalesHistoryApi#dailyDemand}
 * devolviendo lo sembrado. Aserciones relativas sobre los propios datos (L-08): la corrida también
 * escribe filas densas de otros locales y productos, que nadie afirma.
 */
class DailySalesAggregateIT extends AbstractIntegrationIT {

    /** Día de la "corrida del job": la ventana nocturna es [06-12, 06-14]. */
    private static final LocalDate RUN_DATE = LocalDate.of(2030, 6, 15);
    /** Carga inicial del kardex, antes de la primera venta. */
    private static final LocalDate LOAD_DAY = LocalDate.of(2030, 6, 4);
    /** Venta antigua (fuera de la ventana) con una devolución reciente: la rescata el barrido. */
    private static final LocalDate OLD_SALE_DAY = LocalDate.of(2030, 6, 5);
    /** Día con fila preexistente intocable y venta real que el recalculo no debe pisar. */
    private static final LocalDate SENTINEL_DAY = LocalDate.of(2030, 6, 9);
    /** Primer día de la ventana: venta devuelta parcialmente, pack consolidado y venta directa. */
    private static final LocalDate DAY_1 = LocalDate.of(2030, 6, 12);
    /** Día en que se crean las devoluciones (segundo día de la ventana). */
    private static final LocalDate DAY_2 = LocalDate.of(2030, 6, 13);
    /** Tercer día de la ventana: venta que agota el stock y deja saldo cero. */
    private static final LocalDate DAY_3 = LocalDate.of(2030, 6, 14);

    /** Instante viejo de la fila centinela: permite comprobar que no se recalcula. */
    private static final Instant SENTINEL_REFRESHED_AT = Instant.parse("2020-01-01T00:00:00Z");

    private static final LocalTime TEN_AM = LocalTime.of(10, 0);
    private static final LocalTime NOON = LocalTime.of(12, 0);
    private static final LocalTime HALF_PAST_NOON = LocalTime.of(12, 30);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private BusinessClock businessClock;

    @Autowired
    private DailySalesAggregateService aggregateService;

    @Autowired
    private SalesHistoryApi salesHistoryApi;

    private String suffix;
    private long storeId;
    private long userId;
    private long terminalId;
    private long cashSessionId;
    private long categoryId;
    private long baseProduct;       // P1: unidad, con devoluciones y quiebre
    private long secondProduct;      // P2: base del pack y venta directa
    private long packProduct;        // P3: pack de P2 (no debe tener fila propia)
    private long neverStocked;       // P4: activo sin kardex ni ventas
    private long oldSaleLine;        // línea de la venta del 06-05
    private long returnedSaleLine;   // línea de la venta del 06-12

    /**
     * Siembra el escenario completo. Los montos son coherentes con los CHECK de las tablas
     * (neto + impuesto = bruto; devuelto ≤ vendido) porque el agregado lee las tablas reales.
     */
    @BeforeEach
    void seed() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        userId = TestData.insertUser(jdbc);
        terminalId = insertTerminal("T78-" + suffix);
        cashSessionId = insertClosedCashSession();
        categoryId = insertCategory("Categoría T78 " + suffix);

        baseProduct = insertUnitProduct("T78-P1-" + suffix, "Producto base T78 " + suffix, 1000, "500.0000");
        secondProduct = insertUnitProduct("T78-P2-" + suffix, "Producto dos T78 " + suffix, 900, "700.0000");
        neverStocked = insertUnitProduct("T78-P4-" + suffix, "Producto sin stock T78 " + suffix, 1500, "300.0000");
        packProduct = insertPackProduct("T78-P3-" + suffix, "Pack T78 " + suffix, 3800, secondProduct, 4);

        // ---- Kardex de P1: 20 → 17 → 13 → 7 → 8 → 0 ----
        insertMovement(baseProduct, "INITIAL", "INITIAL_LOAD", 0, "20.000", "20.000", LOAD_DAY, TEN_AM);
        long saleA = insertSale(OLD_SALE_DAY, 3000, 2521, 479, "PARTIALLY_RETURNED");
        oldSaleLine = insertSaleLine(saleA, baseProduct, "T78-P1-" + suffix, "UNIT",
                "3.000", baseProduct, "3.000", 1000, 3000, 2521, 479, "500.0000", "1500.00");
        insertMovement(baseProduct, "SALE", "SALE", saleA, "-3.000", "17.000", OLD_SALE_DAY, NOON);

        long saleB = insertSale(SENTINEL_DAY, 4000, 3361, 639, "COMPLETED");
        insertSaleLine(saleB, baseProduct, "T78-P1-" + suffix, "UNIT",
                "4.000", baseProduct, "4.000", 1000, 4000, 3361, 639, "500.0000", "2000.00");
        insertMovement(baseProduct, "SALE", "SALE", saleB, "-4.000", "13.000", SENTINEL_DAY, NOON);

        long saleC = insertSale(DAY_1, 6000, 5042, 958, "PARTIALLY_RETURNED");
        returnedSaleLine = insertSaleLine(saleC, baseProduct, "T78-P1-" + suffix, "UNIT",
                "6.000", baseProduct, "6.000", 1000, 6000, 5042, 958, "500.0000", "3000.00");
        insertMovement(baseProduct, "SALE", "SALE", saleC, "-6.000", "7.000", DAY_1, NOON);

        long saleF = insertSale(DAY_3, 8000, 6723, 1277, "COMPLETED");
        insertSaleLine(saleF, baseProduct, "T78-P1-" + suffix, "UNIT",
                "8.000", baseProduct, "8.000", 1000, 8000, 6723, 1277, "500.0000", "4000.00");

        // ---- Kardex de P2: 30 → 26 (pack) → 24 (directa) ----
        insertMovement(secondProduct, "INITIAL", "INITIAL_LOAD", 0, "30.000", "30.000", LOAD_DAY, TEN_AM);
        long salePack = insertSale(DAY_1, 3800, 3193, 607, "COMPLETED");
        insertSaleLine(salePack, packProduct, "T78-P3-" + suffix, "PACK",
                "1.000", secondProduct, "4.000", 3800, 3800, 3193, 607, "700.0000", "2800.00");
        insertMovement(secondProduct, "SALE", "SALE", salePack, "-4.000", "26.000", DAY_1, NOON);
        long saleE = insertSale(DAY_1, 1800, 1513, 287, "COMPLETED");
        insertSaleLine(saleE, secondProduct, "T78-P2-" + suffix, "UNIT",
                "2.000", secondProduct, "2.000", 900, 1800, 1513, 287, "700.0000", "1400.00");
        insertMovement(secondProduct, "SALE", "SALE", saleE, "-2.000", "24.000", DAY_1, HALF_PAST_NOON);

        // ---- Devoluciones creadas el 06-13 (dentro de la ventana del job) ----
        long returnOfOldSale = insertReturn(saleA, 1000);
        insertReturnLine(returnOfOldSale, oldSaleLine, "1.000", "1.000", 1000, 840, 160);
        long returnOfRecentSale = insertReturn(saleC, 1000);
        insertReturnLine(returnOfRecentSale, returnedSaleLine, "1.000", "1.000", 1000, 840, 160);
        markReturnedQuantity(oldSaleLine);
        markReturnedQuantity(returnedSaleLine);
        insertMovement(baseProduct, "SALE_RETURN", "SALE_RETURN", returnOfRecentSale,
                "1.000", "8.000", DAY_2, NOON);
        insertMovement(baseProduct, "SALE", "SALE", saleF, "-8.000", "0.000", DAY_3, NOON);

        // ---- Fila centinela preexistente para un día fuera del recalculo ----
        insertSentinelRow();
    }

    @Test
    void refreshRecentDaysNetsRefundsFlagsStockoutsAndKeepsOlderDays() {
        int rows = aggregateService.refreshRecentDays(RUN_DATE);
        assertThat(rows).isPositive();

        // Día antiguo rescatable: la devolución del 06-13 netea la venta del 06-05.
        AggregateRow oldDay = rowFor(baseProduct, OLD_SALE_DAY);
        assertThat(oldDay).isNotNull();
        assertThat(oldDay.qtySold()).isEqualByComparingTo("2");
        assertThat(oldDay.grossAmount()).isEqualByComparingTo("2000");
        assertThat(oldDay.netAmount()).isEqualByComparingTo("1681");
        assertThat(oldDay.costAmount()).isEqualByComparingTo("1000.00");
        assertThat(oldDay.transactions()).isEqualTo(1);
        assertThat(oldDay.openingQty()).isEqualByComparingTo("20");
        assertThat(oldDay.closingQty()).isEqualByComparingTo("17");
        assertThat(oldDay.hadStockout()).isFalse();

        // Fila centinela intocable: el recalculo de 3 días no borra lo anterior.
        AggregateRow sentinel = rowFor(baseProduct, SENTINEL_DAY);
        assertThat(sentinel).isNotNull();
        assertThat(sentinel.qtySold()).isEqualByComparingTo("999");
        assertThat(sentinel.transactions()).isEqualTo(99);
        assertThat(sentinel.refreshedAt()).isEqualTo(SENTINEL_REFRESHED_AT);

        // Días intermedios sin recalculo: no se crean.
        assertThat(rowFor(baseProduct, SENTINEL_DAY.plusDays(1))).isNull();
        assertThat(rowFor(baseProduct, DAY_1.minusDays(1))).isNull();

        // Primer día de la ventana: venta neta de la devolución parcial.
        AggregateRow day1 = rowFor(baseProduct, DAY_1);
        assertThat(day1).isNotNull();
        assertThat(day1.qtySold()).isEqualByComparingTo("5");
        assertThat(day1.grossAmount()).isEqualByComparingTo("5000");
        assertThat(day1.netAmount()).isEqualByComparingTo("4202");
        assertThat(day1.costAmount()).isEqualByComparingTo("2500.00");
        assertThat(day1.transactions()).isEqualTo(1);
        assertThat(day1.openingQty()).isEqualByComparingTo("13");
        assertThat(day1.closingQty()).isEqualByComparingTo("7");
        assertThat(day1.hadStockout()).isFalse();
        assertThat(day1.refreshedAt()).isAfter(SENTINEL_REFRESHED_AT);

        // El día de la devolución no queda negativo: el cargo va al día de venta.
        AggregateRow day2 = rowFor(baseProduct, DAY_2);
        assertThat(day2).isNotNull();
        assertThat(day2.qtySold()).isEqualByComparingTo("0");
        assertThat(day2.transactions()).isZero();
        assertThat(day2.openingQty()).isEqualByComparingTo("7");
        assertThat(day2.closingQty()).isEqualByComparingTo("8");
        assertThat(day2.hadStockout()).isFalse();

        // Tercer día: la venta agota el stock → quiebre.
        AggregateRow day3 = rowFor(baseProduct, DAY_3);
        assertThat(day3).isNotNull();
        assertThat(day3.qtySold()).isEqualByComparingTo("8");
        assertThat(day3.grossAmount()).isEqualByComparingTo("8000");
        assertThat(day3.netAmount()).isEqualByComparingTo("6723");
        assertThat(day3.costAmount()).isEqualByComparingTo("4000.00");
        assertThat(day3.transactions()).isEqualTo(1);
        assertThat(day3.openingQty()).isEqualByComparingTo("8");
        assertThat(day3.closingQty()).isEqualByComparingTo("0");
        assertThat(day3.hadStockout()).isTrue();
    }

    @Test
    void refreshConsolidatesPacksAndCensorsProductsWithoutStock() {
        aggregateService.refreshRecentDays(RUN_DATE);

        // P2: el pack (4 unidades base) más la venta directa (2) en la misma fila del base.
        AggregateRow second = rowFor(secondProduct, DAY_1);
        assertThat(second).isNotNull();
        assertThat(second.qtySold()).isEqualByComparingTo("6");
        assertThat(second.grossAmount()).isEqualByComparingTo("5600");
        assertThat(second.netAmount()).isEqualByComparingTo("4706");
        assertThat(second.costAmount()).isEqualByComparingTo("4200.00");
        assertThat(second.transactions()).isEqualTo(2);
        assertThat(second.openingQty()).isEqualByComparingTo("30");
        assertThat(second.closingQty()).isEqualByComparingTo("24");
        assertThat(second.hadStockout()).isFalse();

        // El pack no tiene fila propia: su demanda llega consolidada al producto base.
        assertThat(rowFor(packProduct, DAY_1)).isNull();

        // Producto activo sin carga de stock: días censurados por apertura en cero (B.11).
        AggregateRow empty = rowFor(neverStocked, DAY_1);
        assertThat(empty).isNotNull();
        assertThat(empty.qtySold()).isEqualByComparingTo("0");
        assertThat(empty.transactions()).isZero();
        assertThat(empty.openingQty()).isEqualByComparingTo("0");
        assertThat(empty.closingQty()).isEqualByComparingTo("0");
        assertThat(empty.hadStockout()).isTrue();
    }

    @Test
    void refreshRecentDaysIsIdempotent() {
        aggregateService.refreshRecentDays(RUN_DATE);
        AggregateRow firstPass = rowFor(baseProduct, DAY_1);

        int secondPassRows = aggregateService.refreshRecentDays(RUN_DATE);
        assertThat(secondPassRows).isPositive();

        AggregateRow secondPass = rowFor(baseProduct, DAY_1);
        assertThat(secondPass.qtySold()).isEqualByComparingTo(firstPass.qtySold());
        assertThat(secondPass.grossAmount()).isEqualByComparingTo(firstPass.grossAmount());
        assertThat(secondPass.hadStockout()).isEqualTo(firstPass.hadStockout());
    }

    @Test
    void dailyDemandReturnsSeededHistory() {
        aggregateService.refreshRecentDays(RUN_DATE);

        List<DailyDemand> history =
                salesHistoryApi.dailyDemand(storeId, List.of(baseProduct, secondProduct), OLD_SALE_DAY, DAY_3);

        // Orden estable por producto y fecha.
        assertThat(history).isSortedAccordingTo((a, b) -> {
            int byProduct = Long.compare(a.productId(), b.productId());
            return byProduct != 0 ? byProduct : a.saleDate().compareTo(b.saleDate());
        });

        Map<LocalDate, DailyDemand> base = history.stream()
                .filter(d -> d.productId() == baseProduct)
                .collect(Collectors.toMap(DailyDemand::saleDate, Function.identity()));
        assertThat(base.get(OLD_SALE_DAY).quantity()).isEqualByComparingTo("2");
        assertThat(base.get(DAY_1).quantity()).isEqualByComparingTo("5");
        assertThat(base.get(DAY_1).hadStockout()).isFalse();
        assertThat(base.get(DAY_2).quantity()).isEqualByComparingTo("0");
        assertThat(base.get(DAY_3).quantity()).isEqualByComparingTo("8");
        assertThat(base.get(DAY_3).hadStockout()).isTrue();
        // La fila intocada del centinela se lee tal cual está en el agregado.
        assertThat(base.get(SENTINEL_DAY).quantity()).isEqualByComparingTo("999");

        Map<LocalDate, DailyDemand> second = history.stream()
                .filter(d -> d.productId() == secondProduct)
                .collect(Collectors.toMap(DailyDemand::saleDate, Function.identity()));
        assertThat(second.get(DAY_1).quantity()).isEqualByComparingTo("6");
        assertThat(second.get(DAY_3).quantity()).isEqualByComparingTo("0");

        // El pack se consulta por su producto base; el pack mismo no tiene historia.
        assertThat(salesHistoryApi.dailyDemand(storeId, List.of(packProduct), OLD_SALE_DAY, DAY_3)).isEmpty();

        // Sin productos no hay consulta: lista vacía.
        assertThat(salesHistoryApi.dailyDemand(storeId, List.of(), OLD_SALE_DAY, DAY_3)).isEmpty();
    }

    @Test
    void dailyDemandRejectsInvertedRange() {
        assertThatThrownBy(() -> salesHistoryApi.dailyDemand(
                storeId, List.of(baseProduct), DAY_3, DAY_1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void refreshRangeRecomputesExplicitDaysAndRejectsInvertedRange() {
        // Refresco a demanda (F.2: la corrida de reabastecimiento ejecuta antes el agregado del rango).
        aggregateService.refreshRange(DAY_1, DAY_3);
        AggregateRow day1 = rowFor(baseProduct, DAY_1);
        assertThat(day1).isNotNull();
        assertThat(day1.qtySold()).isEqualByComparingTo("5");
        assertThat(rowFor(baseProduct, DAY_3).hadStockout()).isTrue();
        // El recalculo explícito tampoco toca días fuera del rango pedido.
        assertThat(rowFor(baseProduct, SENTINEL_DAY).qtySold()).isEqualByComparingTo("999");
        assertThat(rowFor(baseProduct, SENTINEL_DAY).refreshedAt()).isEqualTo(SENTINEL_REFRESHED_AT);

        assertThatThrownBy(() -> aggregateService.refreshRange(DAY_3, DAY_1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    /**
     * {@link SalesHistoryApi#refreshDailySales} expone el refresco a demanda para el reabastecimiento
     * (T80): mismo efecto que {@code refreshRange} vía la API pública, sin duplicar su cobertura.
     */
    @Test
    void refreshDailySalesExposesRangeRefresh() {
        int rows = salesHistoryApi.refreshDailySales(DAY_1, DAY_3);
        assertThat(rows).isPositive();

        AggregateRow day1 = rowFor(baseProduct, DAY_1);
        assertThat(day1).isNotNull();
        assertThat(day1.qtySold()).isEqualByComparingTo("5");
        assertThat(rowFor(baseProduct, DAY_3).hadStockout()).isTrue();
        // El centinela queda fuera del rango pedido y no se toca.
        assertThat(rowFor(baseProduct, SENTINEL_DAY).refreshedAt()).isEqualTo(SENTINEL_REFRESHED_AT);

        assertThatThrownBy(() -> salesHistoryApi.refreshDailySales(DAY_3, DAY_1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // -----------------------------------------------------------------------------------------
    // Siembra: INSERT directos con claves únicas (L-08), montos coherentes con los CHECK de B.9.
    // -----------------------------------------------------------------------------------------

    private long insertTerminal(String code) {
        return jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:s, :c, 'Caja T78', true)
                        RETURNING id
                        """)
                .param("s", storeId)
                .param("c", code)
                .query(Long.class)
                .single();
    }

    private long insertClosedCashSession() {
        String number = "TU78-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return jdbc.sql("""
                        INSERT INTO cash_sessions (
                            number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount
                        )
                        VALUES (:n, :s, :t, :c, 'CLOSED', :d, 50000)
                        RETURNING id
                        """)
                .param("n", number)
                .param("s", storeId)
                .param("t", terminalId)
                .param("c", userId)
                .param("d", DAY_1)
                .query(Long.class)
                .single();
    }

    private long insertCategory(String name) {
        return jdbc.sql("INSERT INTO categories (name, active) VALUES (:name, true) RETURNING id")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertUnitProduct(String sku, String name, long priceGross, String avgCost) {
        return jdbc.sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, lot_controlled, active
                        )
                        VALUES (:sku, :name, :cat, 'UNIT', 'UNIT', :price, :cost, false, false, true)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("cat", categoryId)
                .param("price", priceGross)
                .param("cost", new BigDecimal(avgCost))
                .query(Long.class)
                .single();
    }

    private long insertPackProduct(String sku, String name, long priceGross, long baseId, int factor) {
        long packId = jdbc.sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, lot_controlled, active
                        )
                        VALUES (:sku, :name, :cat, 'PACK', 'UNIT', :price, 0, false, false, true)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("cat", categoryId)
                .param("price", priceGross)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        INSERT INTO product_packs (product_id, base_product_id, factor)
                        VALUES (:pack, :base, :factor)
                        """)
                .param("pack", packId)
                .param("base", baseId)
                .param("factor", new BigDecimal(factor))
                .update();
        return packId;
    }

    /**
     * Inserta una venta de una sola línea: los totales de cabecera repiten los de la línea, con
     * descuento, exentos y redondeo en cero, de modo que se cumplan los CHECK de RN-07.
     */
    private long insertSale(LocalDate businessDate, long subtotalGross, long netTotal, long taxTotal,
                            String status) {
        return jdbc.sql("""
                        INSERT INTO sales (
                            number, store_id, terminal_id, cash_session_id, cashier_user_id, status,
                            sold_at, business_date, idempotency_key,
                            subtotal_gross, discount_total, total_gross, net_total, tax_total, exempt_total,
                            rounding_adjustment, total_payable, paid_total, change_total
                        )
                        VALUES (
                            :number, :s, :t, :cs, :u, :status,
                            :soldAt, :d, :idem,
                            :subtotal, 0, :subtotal, :net, :tax, 0,
                            0, :subtotal, :subtotal, 0
                        )
                        RETURNING id
                        """)
                .param("number", "T78S" + suffix + "-" + sequenceNext())
                .param("s", storeId)
                .param("t", terminalId)
                .param("cs", cashSessionId)
                .param("u", userId)
                .param("status", status)
                .param("soldAt", utcAt(businessDate, NOON))
                .param("d", businessDate)
                .param("idem", UUID.randomUUID())
                .param("subtotal", subtotalGross)
                .param("net", netTotal)
                .param("tax", taxTotal)
                .query(Long.class)
                .single();
    }

    private long insertSaleLine(long saleId, long productId, String sku, String saleType,
                                String quantity, long stockProductId, String stockQuantity,
                                long unitPriceGross, long subtotalGross, long net, long tax,
                                String unitCost, String lineCost) {
        return jdbc.sql("""
                        INSERT INTO sale_lines (
                            sale_id, line_number, store_id, business_date, product_id, sku, product_name,
                            sale_type, quantity, stock_product_id, stock_quantity,
                            unit_price_gross, line_subtotal_gross, discount_amount, line_total_gross,
                            line_net, line_tax, line_exempt, unit_cost, line_cost, restricted
                        )
                        VALUES (
                            :saleId, :lineNumber, :s, :d, :productId, :sku, :productName,
                            :saleType, :quantity, :stockProductId, :stockQuantity,
                            :unitPrice, :subtotal, 0, :subtotal,
                            :net, :tax, 0, :unitCost, :lineCost, false
                        )
                        RETURNING id
                        """)
                .param("saleId", saleId)
                .param("lineNumber", lineSeq(saleId))
                .param("s", storeId)
                .param("d", dayOfSale(saleId))
                .param("productId", productId)
                .param("sku", sku)
                .param("productName", "Producto T78")
                .param("saleType", saleType)
                .param("quantity", new BigDecimal(quantity))
                .param("stockProductId", stockProductId)
                .param("stockQuantity", new BigDecimal(stockQuantity))
                .param("unitPrice", unitPriceGross)
                .param("subtotal", subtotalGross)
                .param("net", net)
                .param("tax", tax)
                .param("unitCost", new BigDecimal(unitCost))
                .param("lineCost", new BigDecimal(lineCost))
                .query(Long.class)
                .single();
    }

    /** Devuelve la fecha de negocio de una venta ya insertada (columna desnormalizada). */
    private LocalDate dayOfSale(long saleId) {
        return jdbc.sql("SELECT business_date FROM sales WHERE id = :id")
                .param("id", saleId)
                .query(LocalDate.class)
                .single();
    }

    /** Siguiente número de línea de una venta (una línea por venta en este escenario). */
    private int lineSeq(long saleId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM sale_lines WHERE sale_id = :id")
                .param("id", saleId)
                .query(Long.class)
                .single();
        return count.intValue() + 1;
    }

    private long insertReturn(long saleId, long refundTotal) {
        return jdbc.sql("""
                        INSERT INTO sale_returns (
                            number, sale_id, return_type, store_id, terminal_id, cash_session_id,
                            reason_code, refund_total, idempotency_key, created_by, created_at
                        )
                        VALUES (
                            :number, :saleId, 'RETURN', :s, :t, :cs,
                            'CUSTOMER_REQUEST', :refund, :idem, :u, :createdAt
                        )
                        RETURNING id
                        """)
                .param("number", "T78R" + suffix + "-" + sequenceNext())
                .param("saleId", saleId)
                .param("s", storeId)
                .param("t", terminalId)
                .param("cs", cashSessionId)
                .param("refund", refundTotal)
                .param("idem", UUID.randomUUID())
                .param("u", userId)
                .param("createdAt", utcAt(DAY_2, HALF_PAST_NOON))
                .query(Long.class)
                .single();
    }

    private void insertReturnLine(long returnId, long saleLineId, String quantity, String stockQuantity,
                                  long refundAmount, long refundNet, long refundTax) {
        jdbc.sql("""
                        INSERT INTO sale_return_lines (
                            sale_return_id, sale_line_id, quantity, stock_quantity, restock,
                            refund_amount, refund_net, refund_tax
                        )
                        VALUES (:returnId, :lineId, :quantity, :stockQuantity, true, :amount, :net, :tax)
                        """)
                .param("returnId", returnId)
                .param("lineId", saleLineId)
                .param("quantity", new BigDecimal(quantity))
                .param("stockQuantity", new BigDecimal(stockQuantity))
                .param("amount", refundAmount)
                .param("net", refundNet)
                .param("tax", refundTax)
                .update();
    }

    /** Marca la línea como parcialmente devuelta y la venta como PARTIALLY_RETURNED (B.9). */
    private void markReturnedQuantity(long saleLineId) {
        jdbc.sql("UPDATE sale_lines SET returned_quantity = 1 WHERE id = :id")
                .param("id", saleLineId)
                .update();
        jdbc.sql("UPDATE sales SET status = 'PARTIALLY_RETURNED' "
                        + "WHERE id = (SELECT sale_id FROM sale_lines WHERE id = :id)")
                .param("id", saleLineId)
                .update();
    }

    private void insertMovement(long productId, String movementType, String sourceType, long sourceId,
                                String delta, String balance, LocalDate on, LocalTime at) {
        jdbc.sql("""
                        INSERT INTO stock_movements (
                            store_id, product_id, movement_type, quantity_delta, balance_after, unit_cost,
                            source_type, source_id, user_id, occurred_at
                        )
                        VALUES (:s, :p, :type, :delta, :balance, :cost, :sourceType, :sourceId, :u, :occurredAt)
                        """)
                .param("s", storeId)
                .param("p", productId)
                .param("type", movementType)
                .param("delta", new BigDecimal(delta))
                .param("balance", new BigDecimal(balance))
                .param("cost", productId == baseProduct ? new BigDecimal("500.0000") : new BigDecimal("700.0000"))
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .param("u", userId)
                .param("occurredAt", utcAt(on, at))
                .update();
    }

    /**
     * Fila preexistente del agregado con valores reconocibles: el recalculo de 3 días no debe
     * tocarla (el 06-09 queda fuera de la ventana y ninguna devolución reciente la afecta).
     */
    private void insertSentinelRow() {
        jdbc.sql("""
                        INSERT INTO daily_product_sales (
                            store_id, product_id, sale_date, qty_sold, gross_amount, net_amount, cost_amount,
                            transactions, opening_qty, closing_qty, had_stockout, refreshed_at
                        )
                        VALUES (:s, :p, :d, 999.000, 999999, 999999, 99999.99, 99, 7.000, 7.000, false, :r)
                        """)
                .param("s", storeId)
                .param("p", baseProduct)
                .param("d", SENTINEL_DAY)
                .param("r", OffsetDateTime.ofInstant(SENTINEL_REFRESHED_AT, ZoneOffset.UTC))
                .update();
    }

    /** Instante de una hora local de negocio convertida a UTC para enlazarla como timestamptz. */
    private OffsetDateTime utcAt(LocalDate day, LocalTime time) {
        return OffsetDateTime.ofInstant(
                businessClock.startOfDay(day).plusSeconds(time.toSecondOfDay()), ZoneOffset.UTC);
    }

    /** Contador simple para que number de venta y devolución sean únicos por prueba. */
    private int sequence = 0;

    private String sequenceNext() {
        return String.valueOf(++sequence);
    }

    // -----------------------------------------------------------------------------------------
    // Lectura del agregado para las aserciones.
    // -----------------------------------------------------------------------------------------

    /** Proyección de una fila de {@code daily_product_sales} para afirmar valores exactos. */
    private record AggregateRow(
            BigDecimal qtySold,
            BigDecimal grossAmount,
            BigDecimal netAmount,
            BigDecimal costAmount,
            int transactions,
            BigDecimal openingQty,
            BigDecimal closingQty,
            boolean hadStockout,
            Instant refreshedAt
    ) {
    }

    /** Devuelve la fila del agregado (o {@code null} si el día no se ha recalculado). */
    private AggregateRow rowFor(long productId, LocalDate day) {
        return jdbc.sql("""
                        SELECT qty_sold, gross_amount, net_amount, cost_amount, transactions,
                               opening_qty, closing_qty, had_stockout, refreshed_at
                        FROM daily_product_sales
                        WHERE store_id = :s AND product_id = :p AND sale_date = :d
                        """)
                .param("s", storeId)
                .param("p", productId)
                .param("d", day)
                .query((rs, rowNum) -> new AggregateRow(
                        rs.getBigDecimal("qty_sold"),
                        rs.getBigDecimal("gross_amount"),
                        rs.getBigDecimal("net_amount"),
                        rs.getBigDecimal("cost_amount"),
                        rs.getInt("transactions"),
                        rs.getBigDecimal("opening_qty"),
                        rs.getBigDecimal("closing_qty"),
                        rs.getBoolean("had_stockout"),
                        rs.getObject("refreshed_at", OffsetDateTime.class).toInstant()))
                .optional()
                .orElse(null);
    }
}
