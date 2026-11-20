package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.shared.context.support.ConfigurableStoreAccessValidator;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del flujo 8 de {@code API.md} F.4 (PI de T80): parámetros resueltos por
 * niveles, corrida que refresca el agregado y calcula sugerencias, OC {@code DRAFT} por proveedor
 * con {@code origin=REPLENISHMENT} y líneas trazables, líneas {@code NO_SUPPLIER} fuera de las
 * órdenes, el parámetro GLOBAL no borrable (cubierto en {@link ReplenishmentParamsIT}) y la
 * idempotencia de {@code POST /replenishment/runs}.
 *
 * <p>Semilla propia con claves únicas y aserciones relativas: los cálculos exactos se afirman
 * leyendo las filas de {@code replenishment_lines} de la corrida creada (L-08); la forma del JSON
 * se afirma por HTTP con el filtro de categoría, que aísla los productos de esta prueba.
 */
@Import(ReplenishmentRunIT.StoreValidatorConfig.class)
class ReplenishmentRunIT extends AbstractIntegrationIT {

    @TestConfiguration
    static class StoreValidatorConfig {
        @Bean
        ConfigurableStoreAccessValidator storeAccessValidator() {
            return new ConfigurableStoreAccessValidator();
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int WINDOW = 21; // ventana del parámetro GLOBAL que usa la corrida
    private static final AtomicLong RUT_SEQ = new AtomicLong(
            91_000_000L + (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 3_000_000L));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ConfigurableStoreAccessValidator validator;

    @Autowired
    BusinessClock clock;

    private String suffix;
    private long storeId;
    private long categoryId;
    private long productAId;   // demanda constante, proveedor A, max_qty 120
    private long productBId;   // sin proveedor, min_qty 12 (NO_SUPPLIER)
    private long productCId;   // demanda constante, stock 100 (NO_ACTION)
    private long productDId;   // sin demanda, proveedor B con MOQ 3, min_qty 10
    private long supplierAId;
    private long supplierBId;

    @BeforeEach
    void setUpData() {
        validator.reset();
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // Solo este módulo escribe en replenishment_*: limpiar deja determinista la corrida
        // (en particular la ventana máxima, que se calcula sobre todas las filas de parámetros).
        ownerJdbc().sql("DELETE FROM replenishment_lines").update();
        ownerJdbc().sql("DELETE FROM replenishment_runs").update();
        ownerJdbc().sql("DELETE FROM replenishment_params").update();

        seedData();
    }

    /** Siembra local, terminal, turno cerrado, catálogo, proveedores, stock y ventas de prueba. */
    private void seedData() {
        storeId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        long terminalId = jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:storeId, :code, 'Caja T80', true)
                        RETURNING id
                        """)
                .param("storeId", storeId)
                .param("code", "T" + suffix.substring(0, 5).toUpperCase())
                .query(Long.class)
                .single();
        long cashierId = TestData.insertUser(jdbc);
        jdbc.sql("""
                        INSERT INTO cash_sessions (number, store_id, terminal_id, cashier_user_id, status,
                                business_date, opened_at, opening_amount, closed_at)
                        VALUES (:number, :storeId, :terminalId, :cashierId, 'CLOSED', now()::date - 40,
                                now() - interval '40 days', 0, now())
                        """)
                .param("number", "CS-T80-" + suffix)
                .param("storeId", storeId)
                .param("terminalId", terminalId)
                .param("cashierId", cashierId)
                .update();

        categoryId = ownerJdbc().sql("""
                        INSERT INTO categories (name, active, created_at, updated_at, version)
                        VALUES (:name, true, now(), now(), 0)
                        RETURNING id
                        """)
                .param("name", "Bebidas T80 " + suffix)
                .query(Long.class)
                .single();

        LocalDate created40DaysAgo = LocalDate.now(clock.zone()).minusDays(40);
        productAId = insertProduct("T80-A-" + suffix, "Bebida T80 A " + suffix, created40DaysAgo);
        productBId = insertProduct("T80-B-" + suffix, "Producto T80 B " + suffix, created40DaysAgo);
        productCId = insertProduct("T80-C-" + suffix, "Producto T80 C " + suffix, created40DaysAgo);
        productDId = insertProduct("T80-D-" + suffix, "Producto T80 D " + suffix, created40DaysAgo);

        supplierAId = insertSupplier("Distribuidora A T80 " + suffix, 3, "ARRAY[1,3]");
        supplierBId = insertSupplier("Distribuidora B T80 " + suffix, 2, "ARRAY[5]");
        // A: caja de 10 unidades a $10.000 netos la caja -> costo unitario $1.000; MOQ 1.
        insertProductSupplier(productAId, supplierAId, new BigDecimal("10"), new BigDecimal("10000"),
                new BigDecimal("1"), true);
        // B: bolsa de 5 unidades a $3.000 -> costo unitario $600; MOQ 3.
        insertProductSupplier(productDId, supplierBId, new BigDecimal("5"), new BigDecimal("3000"),
                new BigDecimal("3"), true);

        insertStock(productAId, new BigDecimal("25"), null, new BigDecimal("120"));
        insertStock(productBId, new BigDecimal("0"), new BigDecimal("12"), null);
        insertStock(productCId, new BigDecimal("100"), null, null);
        insertStock(productDId, new BigDecimal("0"), new BigDecimal("10"), null);

        // Kardex inicial: con saldo de apertura mayor que 0 los días de la ventana no quedan
        // censurados (had_stockout = opening_qty = 0).
        insertInitialMovement(productAId, new BigDecimal("25"));
        insertInitialMovement(productCId, new BigDecimal("100"));

        // Ventas de 10 unidades por día de A y C en los 30 días anteriores a hoy: D = 10.0000.
        for (int back = 30; back >= 1; back--) {
            insertSale(LocalDate.now(clock.zone()).minusDays(back), List.of(productAId, productCId));
        }
    }

    // ---------------------------------------------------------------------------------
    // Corrida (flujo 8, pasos 1-3): parámetros por niveles, agregado y cálculo
    // ---------------------------------------------------------------------------------

    /**
     * PI central: el parámetro de local (R=2) resuelve sobre el default; la corrida refresca el
     * agregado, calcula las sugerencias de C.5 y clasifica las líneas: SUGGESTED (A y D),
     * NO_SUPPLIER (B, fuera de las OC) y NO_ACTION (C). Los valores exactos de los ejemplos se
     * afirman leyendo las filas de la corrida.
     */
    @Test
    void runRefreshesAggregateAndCalculatesSuggestions() throws Exception {
        putParam("GLOBAL", null, null, null, "\"windowDays\":" + WINDOW);
        putParam("STORE", storeId, null, null, "\"reviewPeriodDays\":2");

        LocalDate today = clock.businessDate();
        UUID key = UUID.randomUUID();
        MvcResult created = mvc.perform(post("/api/v1/replenishment/runs")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("Idempotency-Key", key.toString())
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.asOfDate").value(today.toString()))
                .andExpect(jsonPath("$.countsByStatus.SUGGESTED").value(2))
                .andExpect(jsonPath("$.countsByStatus.NO_SUPPLIER").value(1))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();
        long runId = extractId(created);

        // La corrida quedó en la BD con sus líneas (conteo relativo: las demás filas son de
        // productos de otras pruebas, siempre NO_ACTION).
        Integer linesCount = jdbc.sql("SELECT lines_count FROM replenishment_runs WHERE id = :id")
                .param("id", runId)
                .query(Integer.class)
                .single();
        assertThat(linesCount).isGreaterThanOrEqualTo(4);

        // Cálculo exacto de la línea de A: D=10, SS=10, ROP=40, R=2 (parámetro de local),
        // T=10·(3+2)+10=60, Need=35 -> n=ceil(35/10)=4, qty=40. Cobertura 25/10=2.5 días.
        LineRow lineA = loadLine(runId, productAId);
        assertThat(lineA.status()).isEqualTo("SUGGESTED");
        assertThat(lineA.validDays()).isEqualTo(WINDOW);
        assertThat(lineA.censoredDays()).isZero();
        assertThat(lineA.avgDailyDemand()).isEqualByComparingTo("10.0000");
        assertThat(lineA.demandStd()).isEqualByComparingTo("0.0000");
        assertThat(lineA.leadTimeDays()).isEqualTo(3);
        assertThat(lineA.reviewPeriodDays()).isEqualTo(2);
        assertThat(lineA.onHand()).isEqualByComparingTo("25.000");
        assertThat(lineA.inTransit()).isEqualByComparingTo("0.000");
        assertThat(lineA.safetyStock()).isEqualByComparingTo("10.0000");
        assertThat(lineA.reorderPoint()).isEqualByComparingTo("40.0000");
        assertThat(lineA.targetLevel()).isEqualByComparingTo("60.0000");
        assertThat(lineA.suggestedPurchaseUnits()).isEqualByComparingTo("4.000");
        assertThat(lineA.suggestedQty()).isEqualByComparingTo("40.000");
        assertThat(lineA.finalPurchaseUnits()).isNull();
        assertThat(lineA.unitCostNet()).isEqualByComparingTo("1000.0000");
        assertThat(lineA.daysOfCover()).isEqualByComparingTo("2.50");
        assertThat(lineA.estimatedStockoutDate()).isEqualTo(today.plusDays(2));
        assertThat(lineA.flags()).isEmpty();

        // B: sin proveedor (NO_SUPPLIER) y sin demanda; se repone hasta min_qty=12 con U=1.
        LineRow lineB = loadLine(runId, productBId);
        assertThat(lineB.status()).isEqualTo("NO_SUPPLIER");
        assertThat(lineB.supplierId()).isNull();
        assertThat(lineB.avgDailyDemand()).isEqualByComparingTo("0.0000");
        assertThat(lineB.suggestedPurchaseUnits()).isEqualByComparingTo("12.000");
        assertThat(lineB.suggestedQty()).isEqualByComparingTo("12.000");
        assertThat(lineB.flags()).contains("NEW_PRODUCT", "CENSORED_DAYS", "LEAD_TIME_ZERO");
        assertThat(lineB.daysOfCover()).isNull();

        // C: IP=100 > ROP=40 -> NO_ACTION sin sugerencia.
        LineRow lineC = loadLine(runId, productCId);
        assertThat(lineC.status()).isEqualTo("NO_ACTION");
        assertThat(lineC.suggestedPurchaseUnits()).isEqualByComparingTo("0.000");

        // D: sin demanda, min_qty=10, U=5 y MOQ=3 -> n=3 (MOQ aplicado), qty=15.
        LineRow lineD = loadLine(runId, productDId);
        assertThat(lineD.status()).isEqualTo("SUGGESTED");
        assertThat(lineD.suggestedPurchaseUnits()).isEqualByComparingTo("3.000");
        assertThat(lineD.suggestedQty()).isEqualByComparingTo("15.000");
        assertThat(lineD.flags()).contains("NEW_PRODUCT", "CENSORED_DAYS", "MOQ_APPLIED");
        assertThat(lineD.flags()).doesNotContain("LEAD_TIME_ZERO");

        // Lectura HTTP con filtro de categoría (aísla los 4 productos de esta prueba): la
        // respuesta expone el producto, el proveedor, la unidad de compra y los costos.
        mvc.perform(get("/api/v1/replenishment/runs/" + runId + "/lines?categoryId=" + categoryId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(authorities())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].product.sku").value("T80-A-" + suffix))
                .andExpect(jsonPath("$.content[0].supplier.id").value(supplierAId))
                .andExpect(jsonPath("$.content[0].supplier.name").value("Distribuidora A T80 " + suffix))
                .andExpect(jsonPath("$.content[0].purchaseUnitName").value("Caja"))
                .andExpect(jsonPath("$.content[0].unitsPerPurchaseUnit").value(10))
                .andExpect(jsonPath("$.content[0].avgDailyDemand").value(10.0000))
                .andExpect(jsonPath("$.content[0].reviewPeriodDays").value(2))
                .andExpect(jsonPath("$.content[0].suggestedPurchaseUnits").value(4.000))
                .andExpect(jsonPath("$.content[0].status").value("SUGGESTED"))
                .andExpect(jsonPath("$.content[0].flags.length()").value(0))
                .andExpect(jsonPath("$.content[0].unitCostNet").value(1000.0000))
                .andExpect(jsonPath("$.content[0].estimatedCost").value(40000.00))
                .andExpect(jsonPath("$.content[1].product.sku").value("T80-B-" + suffix))
                .andExpect(jsonPath("$.content[1].supplier").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[1].purchaseUnitName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[1].flags.length()").value(3));

        // Filtros: por estado y por flag.
        mvc.perform(get("/api/v1/replenishment/runs/" + runId + "/lines?status=SUGGESTED&categoryId="
                        + categoryId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(authorities())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/replenishment/runs/" + runId + "/lines?flag=MOQ_APPLIED")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(authorities())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].product.id").value(productDId));

        // Corrida de otro local → 404; listado del local la contiene.
        mvc.perform(get("/api/v1/replenishment/runs/" + runId)
                        .header("X-Store-Id", String.valueOf(storeId + 9_999_999))
                        .with(user("1").authorities(authorities())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/replenishment/runs")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(authorities())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(runId))
                .andExpect(jsonPath("$.content[0].countsByStatus.SUGGESTED").value(2));
    }

    /** La fecha de corte futura se rechaza con VALIDATION_ERROR. */
    @Test
    void futureAsOfDateIsRejected() throws Exception {
        mvc.perform(post("/api/v1/replenishment/runs")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asOfDate\":\"" + clock.businessDate().plusDays(1) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---------------------------------------------------------------------------------
    // Idempotencia del POST /runs ([I] de F.2, L-04)
    // ---------------------------------------------------------------------------------

    /** El reintento con la misma clave devuelve la corrida ya creada, sin ejecutar otra vez. */
    @Test
    void sameIdempotencyKeyReturnsTheExistingRun() throws Exception {
        UUID key = UUID.randomUUID();

        MvcResult first = mvc.perform(post("/api/v1/replenishment/runs")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("Idempotency-Key", key.toString())
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mvc.perform(post("/api/v1/replenishment/runs")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("Idempotency-Key", key.toString())
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(extractId(first)))
                .andReturn();

        // jsonb normaliza espacios y orden de claves: se compara el JSON, no el texto.
        org.skyscreamer.jsonassert.JSONAssert.assertEquals(
                first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString(),
                true);
        Long runs = jdbc.sql("SELECT COUNT(*) FROM replenishment_runs WHERE store_id = :storeId")
                .param("storeId", storeId)
                .query(Long.class)
                .single();
        assertThat(runs).isEqualTo(1);
    }

    /** Sin Idempotency-Key el endpoint [I] responde 400 VALIDATION_ERROR. */
    @Test
    void runWithoutIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/replenishment/runs")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---------------------------------------------------------------------------------
    // Edición de líneas y generación de borradores (flujo 8, pasos 3-4)
    // ---------------------------------------------------------------------------------

    /**
     * PI: el PATCH edita las unidades finales y el estado; el MOQ y los estados no editables se
     * rechazan; los borradores (filtrados por {@code lineIds}) quedan con {@code origin=REPLENISHMENT},
     * trazables a la corrida y a sus líneas, y las NO_SUPPLIER quedan fuera; ids inválidos devuelven
     * {@code VALIDATION_ERROR}; una línea ya en OC no se edita más.
     */
    @Test
    void patchAndDraftsCreateReplenishmentOrdersPerSupplier() throws Exception {
        long runId = executeRun();

        long lineAId = lineId(runId, productAId);
        long lineBId = lineId(runId, productBId);
        long lineDId = lineId(runId, productDId);

        // Edición de las unidades finales (7 cajas, dentro del MOQ 1 de A) y aceptación.
        mvc.perform(patch("/api/v1/replenishment/lines/" + lineAId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalPurchaseUnits\":7,\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lineAId))
                .andExpect(jsonPath("$.finalPurchaseUnits").value(7.000))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(jdbc.sql("""
                        SELECT final_purchase_units FROM replenishment_lines WHERE id = :id
                        """)
                .param("id", lineAId)
                .query(BigDecimal.class)
                .single())
                .isEqualByComparingTo("7.000");

        // Por debajo del MOQ 3 del proveedor B → VALIDATION_ERROR.
        mvc.perform(patch("/api/v1/replenishment/lines/" + lineDId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalPurchaseUnits\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Una línea NO_SUPPLIER no tiene orden que originar → INVALID_STATE_TRANSITION.
        mvc.perform(patch("/api/v1/replenishment/lines/" + lineBId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // Borradores filtrados por líneas: una OC por proveedor (A con la línea A, B con la línea D).
        MvcResult drafts = mvc.perform(post("/api/v1/replenishment/runs/" + runId + "/purchase-orders")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIds\":[" + lineAId + "," + lineDId + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchaseOrders.length()").value(2))
                .andExpect(jsonPath("$.purchaseOrders[0].supplierId").value(supplierAId))
                .andExpect(jsonPath("$.purchaseOrders[0].linesCount").value(1))
                .andExpect(jsonPath("$.purchaseOrders[0].netTotal").value(70000))
                .andExpect(jsonPath("$.purchaseOrders[1].supplierId").value(supplierBId))
                .andExpect(jsonPath("$.purchaseOrders[1].linesCount").value(1))
                .andExpect(jsonPath("$.purchaseOrders[1].netTotal").value(9000))
                .andReturn();
        var orders = MAPPER.readTree(drafts.getResponse().getContentAsString())
                .get("purchaseOrders");
        long orderAId = orders.get(0).get("id").asLong();
        long orderBId = orders.get(1).get("id").asLong();
        assertThat(orders.get(0).get("number").asString()).isNotBlank();
        assertThat(orders.get(1).get("number").asString()).isNotBlank();

        // En BD: DRAFT, origin=REPLENISHMENT, corrida y línea trazables, unidades finales del PATCH.
        OrderRow orderA = loadOrder(orderAId);
        assertThat(orderA.status()).isEqualTo("DRAFT");
        assertThat(orderA.origin()).isEqualTo("REPLENISHMENT");
        assertThat(orderA.replenishmentRunId()).isEqualTo(runId);
        assertThat(orderA.expectedDate()).isEqualTo(clock.businessDate().plusDays(3));
        assertThat(orderA.notes()).contains(String.valueOf(runId));
        OrderLineRow orderLineA = loadOrderLine(orderAId);
        assertThat(orderLineA.productId()).isEqualTo(productAId);
        assertThat(orderLineA.qtyPurchaseUnits()).isEqualByComparingTo("7");
        assertThat(orderLineA.qtyOrdered()).isEqualByComparingTo("70");
        assertThat(orderLineA.unitCostNet()).isEqualByComparingTo("1000");
        assertThat(orderLineA.replenishmentLineId()).isEqualTo(lineAId);

        OrderRow orderB = loadOrder(orderBId);
        assertThat(orderB.status()).isEqualTo("DRAFT");
        assertThat(orderB.origin()).isEqualTo("REPLENISHMENT");
        assertThat(orderB.replenishmentRunId()).isEqualTo(runId);
        assertThat(orderB.expectedDate()).isEqualTo(clock.businessDate().plusDays(2));
        OrderLineRow orderLineB = loadOrderLine(orderBId);
        assertThat(orderLineB.productId()).isEqualTo(productDId);
        assertThat(orderLineB.qtyPurchaseUnits()).isEqualByComparingTo("3");
        assertThat(orderLineB.qtyOrdered()).isEqualByComparingTo("15");
        assertThat(orderLineB.replenishmentLineId()).isEqualTo(lineDId);

        // La línea de B (NO_SUPPLIER) no entró en ninguna orden.
        Integer usedB = jdbc.sql("""
                        SELECT COUNT(*) FROM purchase_order_lines WHERE replenishment_line_id = :id
                        """)
                .param("id", lineBId)
                .query(Integer.class)
                .single();
        assertThat(usedB).isZero();

        // Ya en OC → el PATCH vuelve a responder INVALID_STATE_TRANSITION.
        mvc.perform(patch("/api/v1/replenishment/lines/" + lineAId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalPurchaseUnits\":8}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // Comportamiento por defecto (sin lineIds): como ya hay líneas ACCEPTED, solo se usa A.
        mvc.perform(post("/api/v1/replenishment/runs/" + runId + "/purchase-orders")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchaseOrders.length()").value(1))
                .andExpect(jsonPath("$.purchaseOrders[0].supplierId").value(supplierAId));

        // Ids de línea que no pertenecen a la corrida → VALIDATION_ERROR.
        mvc.perform(post("/api/v1/replenishment/runs/" + runId + "/purchase-orders")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIds\":[999999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // La corrida de otro local no puede generar borradores.
        mvc.perform(post("/api/v1/replenishment/runs/" + runId + "/purchase-orders")
                        .header("X-Store-Id", String.valueOf(storeId + 9_999_999))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    /** Sin {@code catalog:cost:read} los campos de costo salen nulos (F.1, nota D.2). */
    @Test
    void costsAreHiddenWithoutCostReadPermission() throws Exception {
        long runId = executeRun();

        mvc.perform(get("/api/v1/replenishment/runs/" + runId + "/lines?categoryId=" + categoryId)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_RUN_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].unitCostNet").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].estimatedCost").value(org.hamcrest.Matchers.nullValue()));
    }

    /** Sin permiso de ejecución, la corrida responde 403. */
    @Test
    void runWithoutExecutePermissionIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/replenishment/runs")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_RUN_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ---------------------------------------------------------------------------------
    // Apoyo: siembra y lecturas
    // ---------------------------------------------------------------------------------

    private static SimpleGrantedAuthority[] authorities() {
        return List.of(
                Permissions.REPLENISHMENT_RUN_EXECUTE,
                Permissions.REPLENISHMENT_RUN_READ,
                Permissions.REPLENISHMENT_PARAMS_MANAGE,
                Permissions.PURCHASING_ORDER_CREATE,
                Permissions.CATALOG_COST_READ).stream()
                .map(SimpleGrantedAuthority::new)
                .toList()
                .toArray(new SimpleGrantedAuthority[0]);
    }

    /** Ejecuta una corrida por HTTP y devuelve su id (clave de idempotencia nueva). */
    private long executeRun() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/replenishment/runs")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(user("1").authorities(authorities()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        return extractId(result);
    }

    /** PUT de parámetros por alcance, con permiso de gestión. */
    private void putParam(String scope, Long store, Long category, Long product, String extras)
            throws Exception {
        StringBuilder body = new StringBuilder("{\"scope\":\"" + scope + "\"");
        if (store != null) {
            body.append(",\"storeId\":").append(store);
        }
        if (category != null) {
            body.append(",\"categoryId\":").append(category);
        }
        if (product != null) {
            body.append(",\"productId\":").append(product);
        }
        if (extras != null) {
            body.append(",").append(extras);
        }
        body.append("}");
        mvc.perform(put("/api/v1/replenishment/params")
                        .header("X-Store-Id", String.valueOf(storeId))
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.REPLENISHMENT_PARAMS_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk());
    }

    private long insertProduct(String sku, String name, LocalDate createdAt) {
        return jdbc.sql("""
                        INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                              price_gross, avg_cost, last_cost, vat_exempt, active, created_at)
                        VALUES (:sku, :name, :catId, 'UNIT', 'UNIT', 1900, 1000, 1000, true, true, :createdAt)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("catId", categoryId)
                .param("createdAt", java.time.OffsetDateTime.ofInstant(
                        createdAt.atStartOfDay(clock.zone()).toInstant(), java.time.ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    private long insertSupplier(String legalName, int leadTimeDays, String weekdaysLiteral) {
        return jdbc.sql("""
                        INSERT INTO suppliers (rut, legal_name, payment_terms_type, payment_terms_days,
                                               lead_time_days, delivery_weekdays, active)
                        VALUES (:rut, :legalName, 'CREDIT', 30, :lead, """ + weekdaysLiteral
                        + "::smallint[], true)\n                        RETURNING id")
                .param("rut", nextValidRut())
                .param("legalName", legalName)
                .param("lead", leadTimeDays)
                .query(Long.class)
                .single();
    }

    private void insertProductSupplier(long productId, long supplierId, BigDecimal units,
                                       BigDecimal costPerPurchaseUnit, BigDecimal moq, boolean preferred) {
        jdbc.sql("""
                        INSERT INTO product_suppliers (product_id, supplier_id, purchase_unit_name,
                                                       units_per_purchase_unit, cost_per_purchase_unit,
                                                       min_order_qty, is_preferred, active)
                        VALUES (:pid, :sid, :unitName, :units, :cost, :moq, :preferred, true)
                        """)
                .param("pid", productId)
                .param("sid", supplierId)
                .param("unitName", units.compareTo(BigDecimal.TEN) == 0 ? "Caja" : "Bolsa")
                .param("units", units)
                .param("cost", costPerPurchaseUnit)
                .param("moq", moq)
                .param("preferred", preferred)
                .update();
    }

    private void insertStock(long productId, BigDecimal quantity, BigDecimal min, BigDecimal max) {
        jdbc.sql("""
                        INSERT INTO stock (store_id, product_id, quantity, min_qty, max_qty, updated_at)
                        VALUES (:storeId, :pid, :qty, :min, :max, now())
                        ON CONFLICT (store_id, product_id) DO UPDATE
                            SET quantity = EXCLUDED.quantity, min_qty = EXCLUDED.min_qty,
                                max_qty = EXCLUDED.max_qty
                        """)
                .param("storeId", storeId)
                .param("pid", productId)
                .param("qty", quantity)
                .param("min", min)
                .param("max", max)
                .update();
    }

    private void insertInitialMovement(long productId, BigDecimal balance) {
        jdbc.sql("""
                        INSERT INTO stock_movements (store_id, product_id, movement_type, quantity_delta,
                                                     balance_after, unit_cost, source_type, source_id,
                                                     user_id, occurred_at)
                        VALUES (:storeId, :pid, 'INITIAL', :balance, :balance, 1000, 'INITIAL_LOAD', 0,
                                :userId, now() - interval '40 days')
                        """)
                .param("storeId", storeId)
                .param("pid", productId)
                .param("balance", balance)
                .param("userId", TestData.insertUser(jdbc))
                .update();
    }

    /** Una venta por día con una línea por cada producto indicado (10 unidades, exenta). */
    private void insertSale(LocalDate businessDate, List<Long> productIds) {
        long saleId = jdbc.sql("""
                        INSERT INTO sales (number, store_id, terminal_id, cash_session_id, cashier_user_id,
                                           status, sold_at, business_date, idempotency_key,
                                           subtotal_gross, total_gross, net_total, tax_total, exempt_total,
                                           rounding_adjustment, total_payable, paid_total, change_total)
                        VALUES (:number, :storeId, :terminalId, :sessionId, :cashierId, 'COMPLETED',
                                now(), :businessDate, :idemKey, 38000, 38000, 38000, 0, 0, 0, 38000,
                                38000, 0)
                        RETURNING id
                        """)
                .param("number", "V-T80-" + suffix + "-" + businessDate.getDayOfYear())
                .param("storeId", storeId)
                .param("terminalId", jdbc.sql("SELECT id FROM terminals WHERE store_id = :s LIMIT 1")
                        .param("s", storeId).query(Long.class).single())
                .param("sessionId", jdbc.sql("SELECT id FROM cash_sessions WHERE store_id = :s LIMIT 1")
                        .param("s", storeId).query(Long.class).single())
                .param("cashierId", jdbc.sql("SELECT cashier_user_id FROM cash_sessions WHERE store_id = :s LIMIT 1")
                        .param("s", storeId).query(Long.class).single())
                .param("businessDate", businessDate)
                .param("idemKey", UUID.randomUUID())
                .query(Long.class)
                .single();

        int lineNumber = 1;
        for (Long productId : productIds) {
            String sku = jdbc.sql("SELECT sku FROM products WHERE id = :id")
                    .param("id", productId).query(String.class).single();
            String name = jdbc.sql("SELECT name FROM products WHERE id = :id")
                    .param("id", productId).query(String.class).single();
            jdbc.sql("""
                            INSERT INTO sale_lines (sale_id, line_number, store_id, business_date, product_id,
                                                    sku, product_name, sale_type, quantity, stock_product_id,
                                                    stock_quantity, unit_price_gross, line_subtotal_gross,
                                                    discount_amount, line_total_gross, line_net, line_tax,
                                                    line_exempt, unit_cost, line_cost)
                            VALUES (:saleId, :lineNumber, :storeId, :businessDate, :productId, :sku, :name,
                                    'UNIT', 10, :productId, 10, 1900, 19000, 0, 19000, 19000, 0, 0,
                                    1000, 10000.00)
                            """)
                    .param("saleId", saleId)
                    .param("lineNumber", lineNumber++)
                    .param("storeId", storeId)
                    .param("businessDate", businessDate)
                    .param("productId", productId)
                    .param("sku", sku)
                    .param("name", name)
                    .update();
        }
    }

    private long lineId(long runId, long productId) {
        return jdbc.sql("SELECT id FROM replenishment_lines WHERE run_id = :runId AND product_id = :pid")
                .param("runId", runId)
                .param("pid", productId)
                .query(Long.class)
                .single();
    }

    private LineRow loadLine(long runId, long productId) {
        return jdbc.sql("""
                        SELECT id, product_id, supplier_id, product_supplier_id, valid_days, censored_days,
                               avg_daily_demand, demand_std, lead_time_days, review_period_days, on_hand,
                               in_transit, safety_stock, reorder_point, target_level, suggested_qty,
                               suggested_purchase_units, final_purchase_units, unit_cost_net,
                               estimated_stockout_date, days_of_cover, status, flags
                        FROM replenishment_lines
                        WHERE run_id = :runId AND product_id = :pid
                        """)
                .param("runId", runId)
                .param("pid", productId)
                .query((rs, rowNum) -> new LineRow(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        (Long) rs.getObject("supplier_id"),
                        rs.getInt("valid_days"),
                        rs.getInt("censored_days"),
                        rs.getBigDecimal("avg_daily_demand"),
                        rs.getBigDecimal("demand_std"),
                        rs.getInt("lead_time_days"),
                        rs.getInt("review_period_days"),
                        rs.getBigDecimal("on_hand"),
                        rs.getBigDecimal("in_transit"),
                        rs.getBigDecimal("safety_stock"),
                        rs.getBigDecimal("reorder_point"),
                        rs.getBigDecimal("target_level"),
                        rs.getBigDecimal("suggested_qty"),
                        rs.getBigDecimal("suggested_purchase_units"),
                        rs.getBigDecimal("final_purchase_units"),
                        rs.getBigDecimal("unit_cost_net"),
                        rs.getDate("estimated_stockout_date") != null
                                ? rs.getDate("estimated_stockout_date").toLocalDate() : null,
                        rs.getBigDecimal("days_of_cover"),
                        rs.getString("status"),
                        List.of((String[]) rs.getArray("flags").getArray())))
                .single();
    }

    private OrderRow loadOrder(long orderId) {
        return jdbc.sql("""
                        SELECT status, origin, replenishment_run_id, expected_date, notes
                        FROM purchase_orders WHERE id = :id
                        """)
                .param("id", orderId)
                .query((rs, rowNum) -> new OrderRow(
                        rs.getString("status"),
                        rs.getString("origin"),
                        (Long) rs.getObject("replenishment_run_id"),
                        rs.getDate("expected_date") != null
                                ? rs.getDate("expected_date").toLocalDate() : null,
                        rs.getString("notes")))
                .single();
    }

    private OrderLineRow loadOrderLine(long orderId) {
        return jdbc.sql("""
                        SELECT product_id, qty_purchase_units, qty_ordered, unit_cost_net, replenishment_line_id
                        FROM purchase_order_lines WHERE purchase_order_id = :id
                        """)
                .param("id", orderId)
                .query((rs, rowNum) -> new OrderLineRow(
                        rs.getLong("product_id"),
                        rs.getBigDecimal("qty_purchase_units"),
                        rs.getBigDecimal("qty_ordered"),
                        rs.getBigDecimal("unit_cost_net"),
                        (Long) rs.getObject("replenishment_line_id")))
                .single();
    }

    private static long extractId(MvcResult result) throws java.io.UnsupportedEncodingException {
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private static String nextValidRut() {
        long body = RUT_SEQ.incrementAndGet();
        return body + "-" + computeCheckDigit(body);
    }

    private static char computeCheckDigit(long body) {
        long n = body;
        int sum = 0;
        int factor = 2;
        while (n > 0) {
            sum += (int) ((n % 10) * factor);
            n /= 10;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int rem = 11 - (sum % 11);
        if (rem == 11) {
            return '0';
        }
        if (rem == 10) {
            return 'K';
        }
        return (char) ('0' + rem);
    }

    /** Fila de {@code replenishment_lines} con los valores exactos del cálculo. */
    private record LineRow(Long id,
                           Long productId,
                           Long supplierId,
                           int validDays,
                           int censoredDays,
                           BigDecimal avgDailyDemand,
                           BigDecimal demandStd,
                           int leadTimeDays,
                           int reviewPeriodDays,
                           BigDecimal onHand,
                           BigDecimal inTransit,
                           BigDecimal safetyStock,
                           BigDecimal reorderPoint,
                           BigDecimal targetLevel,
                           BigDecimal suggestedQty,
                           BigDecimal suggestedPurchaseUnits,
                           BigDecimal finalPurchaseUnits,
                           BigDecimal unitCostNet,
                           LocalDate estimatedStockoutDate,
                           BigDecimal daysOfCover,
                           String status,
                           List<String> flags) {
    }

    /** Variante mínima para las filas de {@code purchase_order_lines}. */
    private record OrderLineRow(Long productId,
                                BigDecimal qtyPurchaseUnits,
                                BigDecimal qtyOrdered,
                                BigDecimal unitCostNet,
                                Long replenishmentLineId) {
    }

    /** Fila de {@code purchase_orders} con los datos de trazabilidad. */
    private record OrderRow(String status,
                            String origin,
                            Long replenishmentRunId,
                            LocalDate expectedDate,
                            String notes) {
    }
}
