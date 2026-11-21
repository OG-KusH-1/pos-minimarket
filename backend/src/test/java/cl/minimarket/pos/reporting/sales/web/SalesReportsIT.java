package cl.minimarket.pos.reporting.sales.web;

import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de los reportes de venta (T84, {@code HU-REP-02}, {@code HU-REP-03},
 * {@code HU-REP-05}, {@code HU-REP-06}):
 * <ul>
 *   <li>{@code GET /api/v1/reports/sales} (day, hour, weekday, category, store): cuadra con
 *       ventas menos devoluciones y anulaciones.</li>
 *   <li>Rango de 400 días responde 400 {@code VALIDATION_ERROR}.</li>
 *   <li>{@code sales-by-cashier}: ventas netas, voids, devoluciones, descuentos y overrides.</li>
 *   <li>{@code sales-by-payment-method}: montos netos de reembolsos, porcentajes y transacciones.</li>
 *   <li>{@code product-ranking}: most/least, qty/amount, limit y filtro de categoría (incluye
 *       subcategorías).</li>
 *   <li>{@code no-rotation}: productos con stock sin venta en {@code days} días, consolidación de
 *       packs y visibilidad de {@code valuation} según {@code catalog:cost:read}.</li>
 *   <li>{@code abc}: clases A (≤ 80 %), B (≤ 95 %) y C, más exigencia de
 *       {@code report:margin:read} para {@code metric=margin}.</li>
 *   <li>Permisos y acceso multi-local ({@code STORE_ACCESS_DENIED}).</li>
 * </ul>
 */
class SalesReportsIT extends AbstractIntegrationIT {

    private static final LocalDate OLD_DAY = LocalDate.of(2030, 5, 1);
    private static final LocalDate RECENT_DAY = LocalDate.of(2030, 6, 1);
    private static final LocalDate DAY_1 = LocalDate.of(2030, 6, 10); // Lunes
    private static final LocalDate DAY_2 = LocalDate.of(2030, 6, 11); // Martes

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private BusinessClock businessClock;

    private String suffix;
    private int seq;

    private long storeA;
    private long storeB;
    private long storeUnauthorized;
    private long viewerUserId;
    private long cashier1Id;
    private long cashier2Id;
    private long supervisorId;
    private long terminalA;
    private long terminalB;
    private long sessionA;
    private long sessionB;

    private long catBebidas;
    private long catGaseosas;
    private long catSnacks;

    private long prodA;
    private long prodB;
    private long prodC;
    private long prodD;
    private long prodNoRotOld;
    private long prodNoRotNever;
    private long prodZeroStock;
    private long prodBaseViaPack;
    private long prodPack;

    @BeforeEach
    void seed() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        seq = 0;

        storeA = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeB = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeUnauthorized = TestData.insertStore(jdbc, TestData.uniqueStoreCode());

        viewerUserId = insertUser("viewer_" + suffix, "Gerente T84 " + suffix);
        assignStore(viewerUserId, storeA, true);
        assignStore(viewerUserId, storeB, false);

        cashier1Id = insertUser("caj1_" + suffix, "Cajero Uno " + suffix);
        cashier2Id = insertUser("caj2_" + suffix, "Cajero Dos " + suffix);
        supervisorId = insertUser("sup_" + suffix, "Supervisor T84 " + suffix);

        terminalA = insertTerminal(storeA, "TA-" + suffix);
        terminalB = insertTerminal(storeB, "TB-" + suffix);
        sessionA = insertClosedSession(storeA, terminalA, cashier1Id);
        sessionB = insertClosedSession(storeB, terminalB, cashier2Id);

        catBebidas = insertCategory(null, "Bebidas T84 " + suffix);
        catGaseosas = insertCategory(catBebidas, "Gaseosas T84 " + suffix);
        catSnacks = insertCategory(null, "Snacks T84 " + suffix);

        prodA = insertUnitProduct("T84-A-" + suffix, "Cola 1L " + suffix, catGaseosas, 1190, "600.0000");
        prodB = insertUnitProduct("T84-B-" + suffix, "Papas 150g " + suffix, catSnacks, 1190, "600.0000");
        prodC = insertUnitProduct("T84-C-" + suffix, "Jugo 1.5L " + suffix, catBebidas, 1785, "900.0000");
        prodD = insertUnitProduct("T84-D-" + suffix, "Galleta " + suffix, catSnacks, 695, "300.0000");

        prodNoRotOld = insertUnitProduct("T84-NRO-" + suffix, "Snack Antiguo " + suffix, catSnacks, 1000, "400.0000");
        prodNoRotNever = insertUnitProduct("T84-NRN-" + suffix, "Snack Nuevo " + suffix, catSnacks, 2000, "800.0000");
        prodZeroStock = insertUnitProduct("T84-ZRO-" + suffix, "Sin Stock " + suffix, catSnacks, 1000, "500.0000");
        prodBaseViaPack = insertUnitProduct("T84-BASE-" + suffix, "Lata Base " + suffix, catBebidas, 800, "400.0000");
        prodPack = insertPackProduct("T84-PACK-" + suffix, "Pack 6 Latas " + suffix, catBebidas, 4200, prodBaseViaPack, 6);

        insertStock(storeA, prodA, "20.000");
        insertStock(storeA, prodNoRotOld, "15.000");
        insertStock(storeA, prodNoRotNever, "5.000");
        insertStock(storeA, prodZeroStock, "0.000");
        insertStock(storeA, prodBaseViaPack, "12.000");

        // Venta antigua de prodNoRotOld (hace 41 días respecto de DAY_2) -> sí entra en sin rotación.
        long oldSale = insertSale(storeA, terminalA, sessionA, cashier1Id, OLD_DAY, LocalTime.of(11, 0),
                "COMPLETED", 1000, 0, 1000, 840, 160);
        insertSaleLine(oldSale, 1, storeA, OLD_DAY, prodNoRotOld, "T84-NRO-" + suffix, "Snack Antiguo",
                "UNIT", "1.000", prodNoRotOld, "1.000", 1000, 1000, 0, 1000, 840, 160, "400.0000", "400.00", "0.000");
        insertPayment(oldSale, "CASH", 1000, 1000, 0, "APPROVED");

        // Venta reciente vía PACK (hace 10 días respecto de DAY_2) -> excluye prodBaseViaPack de sin rotación.
        long packSale = insertSale(storeA, terminalA, sessionA, cashier1Id, RECENT_DAY, LocalTime.of(12, 0),
                "COMPLETED", 4200, 0, 4200, 3529, 671);
        insertSaleLine(packSale, 1, storeA, RECENT_DAY, prodPack, "T84-PACK-" + suffix, "Pack 6 Latas",
                "PACK", "1.000", prodBaseViaPack, "6.000", 4200, 4200, 0, 4200, 3529, 671, "2400.0000", "2400.00", "0.000");
        insertPayment(packSale, "DEBIT", 4200, null, 0, "APPROVED");

        // --- Rango principal [DAY_1, DAY_2] en storeA ---
        // Venta 1 (DAY_1 10:00, cajero 1, PARTIALLY_RETURNED): 5x prodA + 4x prodB -> se devuelve 1x prodB.
        long sale1 = insertSale(storeA, terminalA, sessionA, cashier1Id, DAY_1, LocalTime.of(10, 0),
                "PARTIALLY_RETURNED", 10710, 0, 10710, 9000, 1710);
        insertSaleLine(sale1, 1, storeA, DAY_1, prodA, "T84-A-" + suffix, "Cola 1L",
                "UNIT", "5.000", prodA, "5.000", 1190, 5950, 0, 5950, 5000, 950, "600.0000", "3000.00", "0.000");
        long sale1Line2 = insertSaleLine(sale1, 2, storeA, DAY_1, prodB, "T84-B-" + suffix, "Papas 150g",
                "UNIT", "4.000", prodB, "4.000", 1190, 4760, 0, 4760, 4000, 760, "600.0000", "2400.00", "1.000");
        insertPayment(sale1, "CASH", 5710, 6000, 290, "APPROVED");
        insertPayment(sale1, "DEBIT", 5000, null, 0, "APPROVED");

        long ret1 = insertReturn(sale1, "RETURN", storeA, terminalA, sessionA, 1190, cashier1Id, DAY_2, LocalTime.of(9, 0));
        insertReturnLine(ret1, sale1Line2, "1.000", "1.000", 1190, 1000, 190);
        insertReturnRefund(ret1, "CASH", 1190);

        // Venta 2 (DAY_1 10:30, cajero 1, VOIDED): 2x prodD con descuento $200 -> anulada el mismo turno.
        // El descuento de ventas anuladas no debe sumarse en discountTotal.
        long sale2 = insertSale(storeA, terminalA, sessionA, cashier1Id, DAY_1, LocalTime.of(10, 30),
                "VOIDED", 1390, 200, 1190, 1000, 190);
        long sale2Line1 = insertSaleLine(sale2, 1, storeA, DAY_1, prodD, "T84-D-" + suffix, "Galleta",
                "UNIT", "2.000", prodD, "2.000", 695, 1390, 200, 1190, 1000, 190, "300.0000", "600.00", "2.000");
        insertPayment(sale2, "CASH", 1190, 1190, 0, "REVERSED");

        long void1 = insertReturn(sale2, "VOID", storeA, terminalA, sessionA, 1190, cashier1Id, DAY_1, LocalTime.of(10, 35));
        insertReturnLine(void1, sale2Line1, "2.000", "2.000", 1190, 1000, 190);
        insertReturnRefund(void1, "CASH", 1190);

        // Venta 3 (DAY_2 15:00, cajero 2, COMPLETED): 1x prodC + 1x prodD con descuento $100.
        long sale3 = insertSale(storeA, terminalA, sessionA, cashier2Id, DAY_2, LocalTime.of(15, 0),
                "COMPLETED", 2480, 100, 2380, 2000, 380);
        insertSaleLine(sale3, 1, storeA, DAY_2, prodC, "T84-C-" + suffix, "Jugo 1.5L",
                "UNIT", "1.000", prodC, "1.000", 1785, 1785, 0, 1785, 1500, 285, "900.0000", "900.00", "0.000");
        insertSaleLine(sale3, 2, storeA, DAY_2, prodD, "T84-D-" + suffix, "Galleta",
                "UNIT", "1.000", prodD, "1.000", 695, 695, 100, 595, 500, 95, "300.0000", "300.00", "0.000");
        insertPayment(sale3, "TRANSFER", 2380, null, 0, "APPROVED");

        // Overrides para cajero 1 en storeA: 2 GRANTED en rango, 1 DENIED en rango, 1 GRANTED fuera de rango.
        insertOverride("GRANTED", cashier1Id, supervisorId, storeA, DAY_1, LocalTime.of(10, 5));
        insertOverride("GRANTED", cashier1Id, supervisorId, storeA, DAY_2, LocalTime.of(11, 0));
        insertOverride("DENIED", cashier1Id, supervisorId, storeA, DAY_1, LocalTime.of(10, 15));
        insertOverride("GRANTED", cashier1Id, supervisorId, storeA, OLD_DAY, LocalTime.of(11, 0));

        // Venta en storeB (DAY_2 16:00): 1x prodA -> para probar multi-local con storeIds=storeA,storeB.
        long saleB = insertSale(storeB, terminalB, sessionB, cashier2Id, DAY_2, LocalTime.of(16, 0),
                "COMPLETED", 1190, 0, 1190, 1000, 190);
        insertSaleLine(saleB, 1, storeB, DAY_2, prodA, "T84-A-" + suffix, "Cola 1L",
                "UNIT", "1.000", prodA, "1.000", 1190, 1190, 0, 1190, 1000, 190, "600.0000", "600.00", "0.000");
        insertPayment(saleB, "CASH", 1190, 1190, 0, "APPROVED");
    }

    @Test
    void salesReportGroupByDayMatchesSalesMinusReturnsAndConsistentAcrossGroupings() throws Exception {
        MvcResult dayResult = mvc.perform(get("/api/v1/reports/sales")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("groupBy", "day")
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode dayBody = jsonMapper.readTree(dayResult.getResponse().getContentAsString());
        assertThat(dayBody.get("filters").get("storeIds")).hasSize(1);
        assertThat(dayBody.get("filters").get("storeIds").get(0).asLong()).isEqualTo(storeA);
        assertThat(dayBody.get("generatedAt").asString()).isNotBlank();

        JsonNode dayRows = dayBody.get("rows");
        assertThat(dayRows).hasSize(2);

        // DAY_1: 10710 + 1190 - 1190 (return) - 1190 (void) = 9520 bruto, 8000 neto, 1520 iva.
        JsonNode d1 = dayRows.get(0);
        assertThat(d1.get("key").asString()).isEqualTo(DAY_1.toString());
        assertThat(d1.get("transactions").asLong()).isEqualTo(1L);
        assertThat(d1.get("grossTotal").decimalValue()).isEqualByComparingTo("9520");
        assertThat(d1.get("netTotal").decimalValue()).isEqualByComparingTo("8000");
        assertThat(d1.get("taxTotal").decimalValue()).isEqualByComparingTo("1520");
        assertThat(d1.get("discountTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(d1.get("returnsTotal").decimalValue()).isEqualByComparingTo("2380");
        assertThat(d1.get("avgTicket").decimalValue()).isEqualByComparingTo("9520");

        // DAY_2: 2380 bruto, 2000 neto, 380 iva, 100 descuento.
        JsonNode d2 = dayRows.get(1);
        assertThat(d2.get("key").asString()).isEqualTo(DAY_2.toString());
        assertThat(d2.get("transactions").asLong()).isEqualTo(1L);
        assertThat(d2.get("grossTotal").decimalValue()).isEqualByComparingTo("2380");
        assertThat(d2.get("netTotal").decimalValue()).isEqualByComparingTo("2000");
        assertThat(d2.get("taxTotal").decimalValue()).isEqualByComparingTo("380");
        assertThat(d2.get("discountTotal").decimalValue()).isEqualByComparingTo("100");
        assertThat(d2.get("returnsTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(d2.get("avgTicket").decimalValue()).isEqualByComparingTo("2380");

        JsonNode dayTotals = dayBody.get("totals");
        assertThat(dayTotals.get("transactions").asLong()).isEqualTo(2L);
        assertThat(dayTotals.get("grossTotal").decimalValue()).isEqualByComparingTo("11900");
        assertThat(dayTotals.get("netTotal").decimalValue()).isEqualByComparingTo("10000");
        assertThat(dayTotals.get("taxTotal").decimalValue()).isEqualByComparingTo("1900");
        assertThat(dayTotals.get("discountTotal").decimalValue()).isEqualByComparingTo("100");
        assertThat(dayTotals.get("returnsTotal").decimalValue()).isEqualByComparingTo("2380");
        assertThat(dayTotals.get("avgTicket").decimalValue()).isEqualByComparingTo("5950");

        // Las demás agrupaciones cuadran con el mismo total en storeA.
        for (String groupBy : List.of("hour", "weekday", "category", "store")) {
            MvcResult res = mvc.perform(get("/api/v1/reports/sales")
                            .header("X-Store-Id", storeA)
                            .param("from", DAY_1.toString())
                            .param("to", DAY_2.toString())
                            .param("groupBy", groupBy)
                            .with(auth(Permissions.REPORT_SALES_READ)))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode body = jsonMapper.readTree(res.getResponse().getContentAsString());
            JsonNode totals = body.get("totals");
            assertThat(totals.get("transactions").asLong()).isEqualTo(2L);
            assertThat(totals.get("grossTotal").decimalValue()).isEqualByComparingTo("11900");
            assertThat(totals.get("netTotal").decimalValue()).isEqualByComparingTo("10000");
            assertThat(totals.get("discountTotal").decimalValue()).isEqualByComparingTo("100");
            assertThat(totals.get("returnsTotal").decimalValue()).isEqualByComparingTo("2380");

            if ("hour".equals(groupBy)) {
                assertThat(body.get("rows").get(0).get("key").asString()).isEqualTo("10");
                assertThat(body.get("rows").get(1).get("key").asString()).isEqualTo("15");
            } else if ("weekday".equals(groupBy)) {
                assertThat(body.get("rows").get(0).get("label").asString()).isEqualTo("Lunes");
                assertThat(body.get("rows").get(1).get("label").asString()).isEqualTo("Martes");
            }
        }

        // Multi-local: storeIds=storeA,storeB
        MvcResult multiStoreRes = mvc.perform(get("/api/v1/reports/sales")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("storeIds", storeA + "," + storeB)
                        .param("groupBy", "store")
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode multiBody = jsonMapper.readTree(multiStoreRes.getResponse().getContentAsString());
        assertThat(multiBody.get("rows")).hasSize(2);
        assertThat(multiBody.get("totals").get("grossTotal").decimalValue()).isEqualByComparingTo("13090");
        assertThat(multiBody.get("totals").get("transactions").asLong()).isEqualTo(3L);
    }

    @Test
    void rangeOf400DaysReturns400ValidationErrorAcrossReports() throws Exception {
        String from = "2029-01-01";
        String to400 = "2030-02-04"; // 400 días inclusive

        for (String path : List.of(
                "/api/v1/reports/sales",
                "/api/v1/reports/sales-by-cashier",
                "/api/v1/reports/sales-by-payment-method",
                "/api/v1/reports/product-ranking",
                "/api/v1/reports/abc")) {
            mvc.perform(get(path)
                            .header("X-Store-Id", storeA)
                            .param("from", from)
                            .param("to", to400)
                            .with(auth(Permissions.REPORT_SALES_READ)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        mvc.perform(get("/api/v1/reports/no-rotation")
                        .header("X-Store-Id", storeA)
                        .param("from", from)
                        .param("to", to400)
                        .with(auth(Permissions.REPORT_INVENTORY_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Sin fechas obligatorias -> también 400 VALIDATION_ERROR.
        mvc.perform(get("/api/v1/reports/sales")
                        .header("X-Store-Id", storeA)
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void salesByCashierIncludesVoidsReturnsDiscountsAndOverrides() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/reports/sales-by-cashier")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
        Map<Long, JsonNode> byUser = indexByLong(body.get("rows"), "userId");
        assertThat(byUser).containsKeys(cashier1Id, cashier2Id);

        JsonNode c1 = byUser.get(cashier1Id);
        assertThat(c1.get("transactions").asLong()).isEqualTo(1L);
        assertThat(c1.get("total").decimalValue()).isEqualByComparingTo("9520");
        assertThat(c1.get("avgTicket").decimalValue()).isEqualByComparingTo("9520");
        assertThat(c1.get("voids").asLong()).isEqualTo(1L);
        assertThat(c1.get("returns").asLong()).isEqualTo(1L);
        assertThat(c1.get("discountTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(c1.get("overridesCount").asLong()).isEqualTo(2L);

        JsonNode c2 = byUser.get(cashier2Id);
        assertThat(c2.get("transactions").asLong()).isEqualTo(1L);
        assertThat(c2.get("total").decimalValue()).isEqualByComparingTo("2380");
        assertThat(c2.get("voids").asLong()).isEqualTo(0L);
        assertThat(c2.get("returns").asLong()).isEqualTo(0L);
        assertThat(c2.get("discountTotal").decimalValue()).isEqualByComparingTo("100");
        assertThat(c2.get("overridesCount").asLong()).isEqualTo(0L);

        JsonNode totals = body.get("totals");
        assertThat(totals.get("transactions").asLong()).isEqualTo(2L);
        assertThat(totals.get("total").decimalValue()).isEqualByComparingTo("11900");
        assertThat(totals.get("voids").asLong()).isEqualTo(1L);
        assertThat(totals.get("returns").asLong()).isEqualTo(1L);
        assertThat(totals.get("discountTotal").decimalValue()).isEqualByComparingTo("100");
        assertThat(totals.get("overridesCount").asLong()).isEqualTo(2L);
    }

    @Test
    void salesByPaymentMethodNetsRefundsAndComputesPercentages() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/reports/sales-by-payment-method")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
        Map<String, JsonNode> byMethod = indexByString(body.get("rows"), "method");
        assertThat(byMethod).containsKeys("DEBIT", "CASH", "TRANSFER");

        assertThat(byMethod.get("DEBIT").get("total").decimalValue()).isEqualByComparingTo("5000");
        assertThat(byMethod.get("CASH").get("total").decimalValue()).isEqualByComparingTo("4520");
        assertThat(byMethod.get("TRANSFER").get("total").decimalValue()).isEqualByComparingTo("2380");

        BigDecimal pctSum = BigDecimal.ZERO;
        for (JsonNode row : body.get("rows")) {
            pctSum = pctSum.add(row.get("pct").decimalValue());
        }
        assertThat(pctSum).isEqualByComparingTo("100.0000");

        assertThat(body.get("totals").get("transactions").asLong()).isEqualTo(2L);
        assertThat(body.get("totals").get("total").decimalValue()).isEqualByComparingTo("11900");
        assertThat(body.get("totals").get("pct").decimalValue()).isEqualByComparingTo("100.0000");
    }

    @Test
    void productRankingSupportsMostLeastCategoryAndLimitValidation() throws Exception {
        // most by qty, limit=2 -> prodA (5), prodB (3)
        MvcResult mostRes = mvc.perform(get("/api/v1/reports/product-ranking")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("order", "most")
                        .param("metric", "qty")
                        .param("limit", "2")
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode mostRows = jsonMapper.readTree(mostRes.getResponse().getContentAsString()).get("rows");
        assertThat(mostRows).hasSize(2);
        assertThat(mostRows.get(0).get("productId").asLong()).isEqualTo(prodA);
        assertThat(mostRows.get(0).get("qty").decimalValue()).isEqualByComparingTo("5.000");
        assertThat(mostRows.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(mostRows.get(1).get("productId").asLong()).isEqualTo(prodB);
        assertThat(mostRows.get(1).get("qty").decimalValue()).isEqualByComparingTo("3.000");
        assertThat(mostRows.get(1).get("rank").asInt()).isEqualTo(2);

        // least by amount, limit=2 -> prodD (500), prodC (1500)
        MvcResult leastRes = mvc.perform(get("/api/v1/reports/product-ranking")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("order", "least")
                        .param("metric", "amount")
                        .param("limit", "2")
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode leastRows = jsonMapper.readTree(leastRes.getResponse().getContentAsString()).get("rows");
        assertThat(leastRows).hasSize(2);
        assertThat(leastRows.get(0).get("productId").asLong()).isEqualTo(prodD);
        assertThat(leastRows.get(0).get("netSales").decimalValue()).isEqualByComparingTo("500");
        assertThat(leastRows.get(1).get("productId").asLong()).isEqualTo(prodC);
        assertThat(leastRows.get(1).get("netSales").decimalValue()).isEqualByComparingTo("1500");

        // categoryId=catBebidas incluye subcategoría catGaseosas (prodA y prodC).
        MvcResult catRes = mvc.perform(get("/api/v1/reports/product-ranking")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("categoryId", String.valueOf(catBebidas))
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode catRows = jsonMapper.readTree(catRes.getResponse().getContentAsString()).get("rows");
        assertThat(catRows).hasSize(2);
        assertThat(indexByLong(catRows, "productId")).containsOnlyKeys(prodA, prodC);

        // limit > 100 -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/reports/product-ranking")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("limit", "101")
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void noRotationFindsStockedUnsoldProductsAndRespectsCostVisibility() throws Exception {
        // Con catalog:cost:read -> muestra valuation.
        MvcResult withCost = mvc.perform(get("/api/v1/reports/no-rotation")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_2.minusDays(29).toString())
                        .param("to", DAY_2.toString())
                        .param("days", "30")
                        .with(auth(Permissions.REPORT_INVENTORY_READ, Permissions.CATALOG_COST_READ)))
                .andExpect(status().isOk())
                .andReturn();

        Map<Long, JsonNode> rows = indexByLong(
                jsonMapper.readTree(withCost.getResponse().getContentAsString()).get("rows"), "productId");
        assertThat(rows).containsKeys(prodNoRotNever, prodNoRotOld);
        assertThat(rows).doesNotContainKeys(prodZeroStock, prodA, prodBaseViaPack);

        JsonNode neverRow = rows.get(prodNoRotNever);
        assertThat(neverRow.get("stock").decimalValue()).isEqualByComparingTo("5.000");
        assertThat(neverRow.get("valuation").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(neverRow.get("lastSaleAt").isNull()).isTrue();
        assertThat(neverRow.get("daysWithoutSale").isNull()).isTrue();

        JsonNode oldRow = rows.get(prodNoRotOld);
        assertThat(oldRow.get("stock").decimalValue()).isEqualByComparingTo("15.000");
        assertThat(oldRow.get("valuation").decimalValue()).isEqualByComparingTo("6000.00");
        assertThat(oldRow.get("daysWithoutSale").asLong()).isEqualTo(41L);

        // Sin catalog:cost:read -> valuation en null.
        MvcResult withoutCost = mvc.perform(get("/api/v1/reports/no-rotation")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_2.minusDays(29).toString())
                        .param("to", DAY_2.toString())
                        .param("days", "30")
                        .with(auth(Permissions.REPORT_INVENTORY_READ)))
                .andExpect(status().isOk())
                .andReturn();
        Map<Long, JsonNode> hiddenRows = indexByLong(
                jsonMapper.readTree(withoutCost.getResponse().getContentAsString()).get("rows"), "productId");
        assertThat(hiddenRows.get(prodNoRotOld).get("valuation").isNull()).isTrue();
        assertThat(hiddenRows.get(prodNoRotNever).get("valuation").isNull()).isTrue();

        // Sin report:inventory:read -> 403 ACCESS_DENIED.
        mvc.perform(get("/api/v1/reports/no-rotation")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_2.minusDays(29).toString())
                        .param("to", DAY_2.toString())
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void abcClassifiesAt80And95PercentAndProtectsMarginMetric() throws Exception {
        // metric=amount: prodA 50 % (A), prodB 80 % (A), prodC 95 % (B), prodD 100 % (C)
        MvcResult amountRes = mvc.perform(get("/api/v1/reports/abc")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("metric", "amount")
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode amountRows = jsonMapper.readTree(amountRes.getResponse().getContentAsString()).get("rows");
        assertThat(amountRows).hasSize(4);

        assertAbcRow(amountRows.get(0), prodA, "5000", "50.0000", "50.0000", "A");
        assertAbcRow(amountRows.get(1), prodB, "3000", "30.0000", "80.0000", "A");
        assertAbcRow(amountRows.get(2), prodC, "1500", "15.0000", "95.0000", "B");
        assertAbcRow(amountRows.get(3), prodD, "500", "5.0000", "100.0000", "C");

        // metric=margin sin report:margin:read -> 403 ACCESS_DENIED (HU-REP-06)
        mvc.perform(get("/api/v1/reports/abc")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("metric", "margin")
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // metric=margin con report:margin:read -> 200 OK
        MvcResult marginRes = mvc.perform(get("/api/v1/reports/abc")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("metric", "margin")
                        .with(auth(Permissions.REPORT_SALES_READ, Permissions.REPORT_MARGIN_READ)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode marginRows = jsonMapper.readTree(marginRes.getResponse().getContentAsString()).get("rows");
        assertAbcRow(marginRows.get(0), prodA, "2000.00", "50.0000", "50.0000", "A");
        assertAbcRow(marginRows.get(1), prodB, "1200.00", "30.0000", "80.0000", "A");
        assertAbcRow(marginRows.get(2), prodC, "600.00", "15.0000", "95.0000", "B");
        assertAbcRow(marginRows.get(3), prodD, "200.00", "5.0000", "100.0000", "C");
    }

    @Test
    void enforcesPermissionsAndStoreAccess() throws Exception {
        // Sin permiso report:sales:read -> 403 ACCESS_DENIED
        mvc.perform(get("/api/v1/reports/sales")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .with(auth(Permissions.REPORT_INVENTORY_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // storeIds incluye un local no asignado al usuario -> 403 STORE_ACCESS_DENIED
        mvc.perform(get("/api/v1/reports/sales")
                        .header("X-Store-Id", storeA)
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("storeIds", storeA + "," + storeUnauthorized)
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

        // Sin header X-Store-Id (no es [G]) -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/reports/sales")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .with(auth(Permissions.REPORT_SALES_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // -----------------------------------------------------------------------------------------
    // Helpers de autenticación, aserción y siembra
    // -----------------------------------------------------------------------------------------

    private RequestPostProcessor auth(String... permissionCodes) {
        List<GrantedAuthority> authorities = Arrays.stream(permissionCodes)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        return user(String.valueOf(viewerUserId)).authorities(authorities);
    }

    private static void assertAbcRow(
            JsonNode row,
            long expectedProductId,
            String expectedMetricValue,
            String expectedPct,
            String expectedCumulativePct,
            String expectedClass
    ) {
        assertThat(row.get("productId").asLong()).isEqualTo(expectedProductId);
        assertThat(row.get("metricValue").decimalValue()).isEqualByComparingTo(expectedMetricValue);
        assertThat(row.get("pct").decimalValue()).isEqualByComparingTo(expectedPct);
        assertThat(row.get("cumulativePct").decimalValue()).isEqualByComparingTo(expectedCumulativePct);
        assertThat(row.get("abcClass").asString()).isEqualTo(expectedClass);
    }

    private static Map<Long, JsonNode> indexByLong(JsonNode array, String field) {
        Map<Long, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode item : array) {
            map.put(item.get(field).asLong(), item);
        }
        return map;
    }

    private static Map<String, JsonNode> indexByString(JsonNode array, String field) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode item : array) {
            map.put(item.get(field).asString(), item);
        }
        return map;
    }

    private long insertUser(String username, String fullName) {
        return jdbc.sql("""
                        INSERT INTO users (username, full_name, password_hash)
                        VALUES (:u, :name, 'x')
                        RETURNING id
                        """)
                .param("u", username)
                .param("name", fullName)
                .query(Long.class)
                .single();
    }

    private void assignStore(long userId, long storeId, boolean isDefault) {
        jdbc.sql("""
                        INSERT INTO user_stores (user_id, store_id, is_default)
                        VALUES (:u, :s, :def)
                        ON CONFLICT (user_id, store_id) DO NOTHING
                        """)
                .param("u", userId)
                .param("s", storeId)
                .param("def", isDefault)
                .update();
    }

    private long insertTerminal(long storeId, String code) {
        return jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:s, :c, :n, true)
                        RETURNING id
                        """)
                .param("s", storeId)
                .param("c", code)
                .param("n", "Caja " + code)
                .query(Long.class)
                .single();
    }

    private long insertClosedSession(long storeId, long terminalId, long cashierId) {
        return jdbc.sql("""
                        INSERT INTO cash_sessions (
                            number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount
                        )
                        VALUES (:n, :s, :t, :c, 'CLOSED', :d, 50000)
                        RETURNING id
                        """)
                .param("n", "T84CS-" + suffix + "-" + (++seq))
                .param("s", storeId)
                .param("t", terminalId)
                .param("c", cashierId)
                .param("d", DAY_1)
                .query(Long.class)
                .single();
    }

    private long insertCategory(Long parentId, String name) {
        return jdbc.sql("""
                        INSERT INTO categories (parent_id, name, active)
                        VALUES (:parentId, :name, true)
                        RETURNING id
                        """)
                .param("parentId", parentId, Types.BIGINT)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertUnitProduct(String sku, String name, long categoryId, long priceGross, String avgCost) {
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

    private long insertPackProduct(String sku, String name, long categoryId, long priceGross, long baseId, int factor) {
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

    private void insertStock(long storeId, long productId, String qty) {
        jdbc.sql("""
                        INSERT INTO stock (store_id, product_id, quantity)
                        VALUES (:s, :p, :q)
                        ON CONFLICT (store_id, product_id) DO UPDATE SET quantity = EXCLUDED.quantity
                        """)
                .param("s", storeId)
                .param("p", productId)
                .param("q", new BigDecimal(qty))
                .update();
    }

    private long insertSale(
            long storeId,
            long terminalId,
            long cashSessionId,
            long cashierId,
            LocalDate businessDate,
            LocalTime time,
            String status,
            long subtotalGross,
            long discountTotal,
            long totalGross,
            long netTotal,
            long taxTotal
    ) {
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
                            :subtotal, :discount, :totalGross, :net, :tax, 0,
                            0, :totalGross, :totalGross, 0
                        )
                        RETURNING id
                        """)
                .param("number", "T84S-" + suffix + "-" + (++seq))
                .param("s", storeId)
                .param("t", terminalId)
                .param("cs", cashSessionId)
                .param("u", cashierId)
                .param("status", status)
                .param("soldAt", utcAt(businessDate, time), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("d", businessDate)
                .param("idem", UUID.randomUUID())
                .param("subtotal", subtotalGross)
                .param("discount", discountTotal)
                .param("totalGross", totalGross)
                .param("net", netTotal)
                .param("tax", taxTotal)
                .query(Long.class)
                .single();
    }

    private long insertSaleLine(
            long saleId,
            int lineNumber,
            long storeId,
            LocalDate businessDate,
            long productId,
            String sku,
            String productName,
            String saleType,
            String quantity,
            long stockProductId,
            String stockQuantity,
            long unitPriceGross,
            long subtotalGross,
            long discountAmount,
            long lineTotalGross,
            long lineNet,
            long lineTax,
            String unitCost,
            String lineCost,
            String returnedQty
    ) {
        return jdbc.sql("""
                        INSERT INTO sale_lines (
                            sale_id, line_number, store_id, business_date, product_id, sku, product_name,
                            sale_type, quantity, stock_product_id, stock_quantity,
                            unit_price_gross, line_subtotal_gross, discount_amount, line_total_gross,
                            line_net, line_tax, line_exempt, unit_cost, line_cost, restricted, returned_quantity
                        )
                        VALUES (
                            :saleId, :lineNumber, :s, :d, :productId, :sku, :productName,
                            :saleType, :quantity, :stockProductId, :stockQuantity,
                            :unitPrice, :subtotal, :discount, :lineTotal,
                            :net, :tax, 0, :unitCost, :lineCost, false, :returnedQty
                        )
                        RETURNING id
                        """)
                .param("saleId", saleId)
                .param("lineNumber", lineNumber)
                .param("s", storeId)
                .param("d", businessDate)
                .param("productId", productId)
                .param("sku", sku)
                .param("productName", productName)
                .param("saleType", saleType)
                .param("quantity", new BigDecimal(quantity))
                .param("stockProductId", stockProductId)
                .param("stockQuantity", new BigDecimal(stockQuantity))
                .param("unitPrice", unitPriceGross)
                .param("subtotal", subtotalGross)
                .param("discount", discountAmount)
                .param("lineTotal", lineTotalGross)
                .param("net", lineNet)
                .param("tax", lineTax)
                .param("unitCost", new BigDecimal(unitCost))
                .param("lineCost", new BigDecimal(lineCost))
                .param("returnedQty", new BigDecimal(returnedQty))
                .query(Long.class)
                .single();
    }

    private void insertPayment(
            long saleId,
            String method,
            long amount,
            Integer tenderedAmount,
            long changeAmount,
            String status
    ) {
        jdbc.sql("""
                        INSERT INTO sale_payments (
                            sale_id, method, amount, tendered_amount, change_amount, status
                        )
                        VALUES (:saleId, :method, :amount, :tendered, :change, :status)
                        """)
                .param("saleId", saleId)
                .param("method", method)
                .param("amount", amount)
                .param("tendered", tenderedAmount, Types.NUMERIC)
                .param("change", changeAmount)
                .param("status", status)
                .update();
    }

    private long insertReturn(
            long saleId,
            String returnType,
            long storeId,
            long terminalId,
            long cashSessionId,
            long refundTotal,
            long createdBy,
            LocalDate date,
            LocalTime time
    ) {
        return jdbc.sql("""
                        INSERT INTO sale_returns (
                            number, sale_id, return_type, store_id, terminal_id, cash_session_id,
                            reason_code, refund_total, idempotency_key, created_by, created_at
                        )
                        VALUES (
                            :number, :saleId, :returnType, :s, :t, :cs,
                            'CUSTOMER_REQUEST', :refundTotal, :idem, :createdBy, :createdAt
                        )
                        RETURNING id
                        """)
                .param("number", "T84R-" + suffix + "-" + (++seq))
                .param("saleId", saleId)
                .param("returnType", returnType)
                .param("s", storeId)
                .param("t", terminalId)
                .param("cs", cashSessionId)
                .param("refundTotal", refundTotal)
                .param("idem", UUID.randomUUID())
                .param("createdBy", createdBy)
                .param("createdAt", utcAt(date, time), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class)
                .single();
    }

    private void insertReturnLine(
            long returnId,
            long saleLineId,
            String qty,
            String stockQty,
            long refundAmount,
            long refundNet,
            long refundTax
    ) {
        jdbc.sql("""
                        INSERT INTO sale_return_lines (
                            sale_return_id, sale_line_id, quantity, stock_quantity, restock,
                            refund_amount, refund_net, refund_tax
                        )
                        VALUES (:returnId, :saleLineId, :qty, :stockQty, true, :amount, :net, :tax)
                        """)
                .param("returnId", returnId)
                .param("saleLineId", saleLineId)
                .param("qty", new BigDecimal(qty))
                .param("stockQty", new BigDecimal(stockQty))
                .param("amount", refundAmount)
                .param("net", refundNet)
                .param("tax", refundTax)
                .update();
    }

    private void insertReturnRefund(long returnId, String method, long amount) {
        jdbc.sql("""
                        INSERT INTO sale_return_refunds (sale_return_id, method, amount)
                        VALUES (:returnId, :method, :amount)
                        """)
                .param("returnId", returnId)
                .param("method", method)
                .param("amount", amount)
                .update();
    }

    private void insertOverride(
            String status,
            long requestedBy,
            long authorizedBy,
            long storeId,
            LocalDate date,
            LocalTime time
    ) {
        jdbc.sql("""
                        INSERT INTO supervisor_authorizations (
                            action, status, requested_by, authorized_by, store_id, method,
                            context, context_hash, created_at
                        )
                        VALUES (
                            'DISCOUNT_TOTAL_OVER_LIMIT', :status, :req, :auth, :storeId, 'PIN',
                            '{}'::jsonb, :hash, :createdAt
                        )
                        """)
                .param("status", status)
                .param("req", requestedBy)
                .param("auth", authorizedBy)
                .param("storeId", storeId)
                .param("hash", "0".repeat(64))
                .param("createdAt", utcAt(date, time), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private OffsetDateTime utcAt(LocalDate day, LocalTime time) {
        return OffsetDateTime.ofInstant(
                businessClock.startOfDay(day).plusSeconds(time.toSecondOfDay()),
                ZoneOffset.UTC);
    }
}
