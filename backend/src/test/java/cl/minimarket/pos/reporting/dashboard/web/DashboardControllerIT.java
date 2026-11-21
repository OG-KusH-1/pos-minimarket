package cl.minimarket.pos.reporting.dashboard.web;

import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba de integración del dashboard diario ({@code GET /api/v1/reports/dashboard}, T83,
 * {@code HU-REP-01}).
 *
 * <p>Usa locales propios con códigos únicos y fechas controladas (L-08) para verificar con
 * exactitud: ventas netas de devoluciones, exclusión de ventas {@code VOIDED}, transacciones,
 * ticket promedio con redondeo {@code HALF_UP}, variación contra el día anterior, serie horaria de
 * 24 horas en zona de negocio ({@code America/Santiago}), ranking top 10 de productos, mix de
 * medios de pago, las 7 alertas operativas (incluyendo overrides {@code STORE} de vencimientos y
 * tolerancia de caja), consolidación multi-local y seguridad ({@code report:dashboard:read} y
 * {@code X-Store-Id}).
 */
class DashboardControllerIT extends AbstractIntegrationIT {

    /** Día de negocio controlado (invierno en Santiago, UTC-4, sin cambio de hora). */
    private static final LocalDate TARGET_DATE = LocalDate.of(2030, 7, 15);
    private static final LocalDate PREVIOUS_DATE = TARGET_DATE.minusDays(1);
    private static final BigDecimal IVA_DIVISOR = new BigDecimal("1.19");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private BusinessClock businessClock;

    private String suffix;
    private long storeA;
    private long storeB;
    private long managerId;
    private long adminId;
    private long approverId;
    private long openSessionCashierId;
    private long terminalA1;
    private long terminalA2;
    private long terminalB1;
    private long closedSessionA;
    private long closedSessionB;
    private long categoryMain;
    private long categoryOther;
    private int seq;

    private final List<Long> productIds = new ArrayList<>();

    @BeforeEach
    void seedScenario() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        seq = 0;

        storeA = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeB = TestData.insertStore(jdbc, TestData.uniqueStoreCode());

        managerId = TestData.insertUser(jdbc);
        adminId = TestData.insertUser(jdbc);
        approverId = TestData.insertUser(jdbc);
        openSessionCashierId = TestData.insertUser(jdbc);
        assignStore(managerId, storeA, true);
        assignRole(adminId, "ADMIN");
        assignStore(approverId, storeA, true);
        assignStore(openSessionCashierId, storeA, true);

        terminalA1 = insertTerminal(storeA, "DA1-" + suffix);
        terminalA2 = insertTerminal(storeA, "DA2-" + suffix);
        terminalB1 = insertTerminal(storeB, "DB1-" + suffix);

        closedSessionA = insertClosedSession(storeA, terminalA1, managerId, TARGET_DATE, "0", null);
        closedSessionB = insertClosedSession(storeB, terminalB1, approverId, TARGET_DATE, "0", null);

        categoryMain = insertCategory("Cat Dash A " + suffix);
        categoryOther = insertCategory("Cat Dash B " + suffix);

        // 12 productos para probar corte exacto en Top 10 (P1..P12)
        productIds.clear();
        for (int i = 1; i <= 12; i++) {
            productIds.add(insertProduct(
                    "T83-P" + i + "-" + suffix,
                    "Producto Dash " + String.format("%02d", i) + " " + suffix,
                    categoryMain,
                    1000L * i,
                    "500.0000"));
        }
    }

    @Test
    void computesSalesTransactionsAvgTicketVsPrevDayHourlyTop10PaymentMixAndAlerts() throws Exception {
        seedSalesAndReturns();
        seedAlertsInStoreA();

        MvcResult res = mvc.perform(get("/api/v1/reports/dashboard")
                        .header("X-Store-Id", storeA)
                        .param("date", TARGET_DATE.toString())
                        .with(dashboardManagerAuth(managerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(TARGET_DATE.toString()))
                .andExpect(jsonPath("$.transactions").value(3))
                .andExpect(jsonPath("$.salesByHour.length()").value(24))
                .andExpect(jsonPath("$.salesByHour[10].hour").value(10))
                .andExpect(jsonPath("$.salesByHour[10].transactions").value(2))
                .andExpect(jsonPath("$.salesByHour[15].hour").value(15))
                .andExpect(jsonPath("$.salesByHour[15].transactions").value(1))
                .andExpect(jsonPath("$.salesByHour[8].transactions").value(0))
                .andExpect(jsonPath("$.topProducts.length()").value(10))
                .andExpect(jsonPath("$.alerts.belowMin").value(2))
                .andExpect(jsonPath("$.alerts.zeroStock").value(2))
                .andExpect(jsonPath("$.alerts.expiringLots").value(2))
                .andExpect(jsonPath("$.alerts.pendingShrinkages").value(2))
                .andExpect(jsonPath("$.alerts.pendingAdjustments").value(1))
                .andExpect(jsonPath("$.alerts.pendingCounts").value(1))
                .andExpect(jsonPath("$.alerts.pendingCashDifferences").value(1))
                .andReturn();

        JsonNode root = jsonMapper.readTree(res.getResponse().getContentAsString());

        // Venta 1 (25000) + Venta 2 neta (15000 - 3000 = 12000) + Venta 3 (61000) = 98000;
        // VOIDED excluida y venta de storeB aislada
        assertThat(root.get("salesTotal").decimalValue()).isEqualByComparingTo("98000");
        // 98000 / 3 = 32666.666... -> 32667 (HALF_UP)
        assertThat(root.get("avgTicket").decimalValue()).isEqualByComparingTo("32667");
        // Día anterior: 50000 -> (98000 - 50000) / 50000 * 100 = 96.00
        assertThat(root.get("vsPreviousDayPct").decimalValue()).isEqualByComparingTo("96.00");

        JsonNode hourly = root.get("salesByHour");
        assertThat(hourly.get(10).get("total").decimalValue()).isEqualByComparingTo("37000");
        assertThat(hourly.get(15).get("total").decimalValue()).isEqualByComparingTo("61000");
        assertThat(hourly.get(8).get("total").decimalValue()).isEqualByComparingTo("0");

        // Top 10 ordenado por total DESC: P1(20000), P3(12000 neto), P4(11000), P5(10000),
        // P6(9000), P7(8000), P8(7000), P9(6000), P2(5000), P10(5000). P11(3000) y P12(2000) quedan fuera.
        JsonNode top = root.get("topProducts");
        assertThat(top.get(0).get("productId").asLong()).isEqualTo(productIds.get(0));
        assertThat(top.get(0).get("qty").decimalValue()).isEqualByComparingTo("2");
        assertThat(top.get(0).get("total").decimalValue()).isEqualByComparingTo("20000");

        assertThat(top.get(1).get("productId").asLong()).isEqualTo(productIds.get(2));
        assertThat(top.get(1).get("qty").decimalValue()).isEqualByComparingTo("4");
        assertThat(top.get(1).get("total").decimalValue()).isEqualByComparingTo("12000");

        assertThat(top.get(9).get("productId").asLong()).isEqualTo(productIds.get(9));
        assertThat(top.get(9).get("total").decimalValue()).isEqualByComparingTo("5000");

        List<Long> topIds = new ArrayList<>();
        top.forEach(node -> topIds.add(node.get("productId").asLong()));
        assertThat(topIds).doesNotContain(productIds.get(10), productIds.get(11));

        // Mix de pagos: TRANSFER=41000, CASH=30000 (25000 + 5000), CREDIT=20000, DEBIT=7000 (10000 - 3000)
        JsonNode mix = root.get("paymentMix");
        assertThat(mix).hasSize(4);
        assertThat(mix.get(0).get("method").asString()).isEqualTo("TRANSFER");
        assertThat(mix.get(0).get("total").decimalValue()).isEqualByComparingTo("41000");
        assertThat(mix.get(1).get("method").asString()).isEqualTo("CASH");
        assertThat(mix.get(1).get("total").decimalValue()).isEqualByComparingTo("30000");
        assertThat(mix.get(2).get("method").asString()).isEqualTo("CREDIT");
        assertThat(mix.get(2).get("total").decimalValue()).isEqualByComparingTo("20000");
        assertThat(mix.get(3).get("method").asString()).isEqualTo("DEBIT");
        assertThat(mix.get(3).get("total").decimalValue()).isEqualByComparingTo("7000");
    }

    @Test
    void defaultsToBusinessClockTodayAndReturnsZeroesOnEmptyStore() throws Exception {
        LocalDate expectedToday = businessClock.businessDate();

        MvcResult res = mvc.perform(get("/api/v1/reports/dashboard")
                        .header("X-Store-Id", storeB)
                        .with(adminAuth(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(expectedToday.toString()))
                .andExpect(jsonPath("$.transactions").value(0))
                .andExpect(jsonPath("$.vsPreviousDayPct").isEmpty())
                .andExpect(jsonPath("$.salesByHour.length()").value(24))
                .andExpect(jsonPath("$.topProducts.length()").value(0))
                .andExpect(jsonPath("$.paymentMix.length()").value(0))
                .andExpect(jsonPath("$.alerts.belowMin").value(0))
                .andExpect(jsonPath("$.alerts.zeroStock").value(0))
                .andExpect(jsonPath("$.alerts.expiringLots").value(0))
                .andExpect(jsonPath("$.alerts.pendingShrinkages").value(0))
                .andExpect(jsonPath("$.alerts.pendingAdjustments").value(0))
                .andExpect(jsonPath("$.alerts.pendingCounts").value(0))
                .andExpect(jsonPath("$.alerts.pendingCashDifferences").value(0))
                .andReturn();

        JsonNode root = jsonMapper.readTree(res.getResponse().getContentAsString());
        assertThat(root.get("salesTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(root.get("avgTicket").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void multiStoreConsolidatesForAllAccessAdminAndRejectsUnassignedOrInvalidStoreIds() throws Exception {
        seedSalesAndReturns();

        // Admin con security:store:all-access consolida storeA (98000, 3 tx) + storeB (12000, 1 tx) = 110000 (4 tx)
        MvcResult res = mvc.perform(get("/api/v1/reports/dashboard")
                        .header("X-Store-Id", storeA)
                        .param("date", TARGET_DATE.toString())
                        .param("storeIds", String.valueOf(storeA), String.valueOf(storeB))
                        .with(adminAuth(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").value(4))
                .andReturn();

        JsonNode root = jsonMapper.readTree(res.getResponse().getContentAsString());
        assertThat(root.get("salesTotal").decimalValue()).isEqualByComparingTo("110000");
        assertThat(root.get("avgTicket").decimalValue()).isEqualByComparingTo("27500");

        // Manager asignado solo a storeA NO puede pedir storeB en storeIds -> 403 STORE_ACCESS_DENIED
        mvc.perform(get("/api/v1/reports/dashboard")
                        .header("X-Store-Id", storeA)
                        .param("date", TARGET_DATE.toString())
                        .param("storeIds", storeA + "," + storeB)
                        .with(dashboardManagerAuth(managerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

        // storeId no positivo -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/reports/dashboard")
                        .header("X-Store-Id", storeA)
                        .param("storeIds", "0")
                        .with(dashboardManagerAuth(managerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void enforcesPermissionAndStoreContext() throws Exception {
        // Sin report:dashboard:read -> 403 ACCESS_DENIED
        mvc.perform(get("/api/v1/reports/dashboard")
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(managerId)).authorities(
                                new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Usuario sin acceso a storeB -> 403 STORE_ACCESS_DENIED
        mvc.perform(get("/api/v1/reports/dashboard")
                        .header("X-Store-Id", storeB)
                        .with(dashboardManagerAuth(managerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

        // Sin X-Store-Id -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/reports/dashboard")
                        .with(dashboardManagerAuth(managerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Sin autenticación -> 401 UNAUTHENTICATED
        mvc.perform(get("/api/v1/reports/dashboard")
                        .header("X-Store-Id", storeA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // -----------------------------------------------------------------------------------------
    // Siembra del escenario de ventas y alertas
    // -----------------------------------------------------------------------------------------

    private void seedSalesAndReturns() {
        // Día anterior en storeA (2030-07-14): 50.000
        long prevSale = insertSale(storeA, terminalA1, closedSessionA, managerId,
                PREVIOUS_DATE, LocalTime.of(16, 0), 50000L, "COMPLETED");
        insertSaleLine(prevSale, storeA, PREVIOUS_DATE, 1, productIds.get(0), "T83-P1-" + suffix,
                "Producto 1", "5.000", 10000L, 50000L);
        insertPayment(prevSale, "CASH", 50000L, 50000L, "APPROVED");

        // Venta 1 (10:15): P1 (2 x 10000 = 20000) + P2 (1 x 5000 = 5000) = 25000 en CASH
        long sale1 = insertSale(storeA, terminalA1, closedSessionA, managerId,
                TARGET_DATE, LocalTime.of(10, 15), 25000L, "COMPLETED");
        insertSaleLine(sale1, storeA, TARGET_DATE, 1, productIds.get(0), "T83-P1-" + suffix,
                "Producto 1", "2.000", 10000L, 20000L);
        insertSaleLine(sale1, storeA, TARGET_DATE, 2, productIds.get(1), "T83-P2-" + suffix,
                "Producto 2", "1.000", 5000L, 5000L);
        insertPayment(sale1, "CASH", 25000L, 25000L, "APPROVED");

        // Venta 2 (10:45): P3 (5 x 3000 = 15000), con devolución parcial de 1 u. (3000) en DEBIT -> neto 12000
        long sale2 = insertSale(storeA, terminalA1, closedSessionA, managerId,
                TARGET_DATE, LocalTime.of(10, 45), 15000L, "PARTIALLY_RETURNED");
        long sale2Line = insertSaleLine(sale2, storeA, TARGET_DATE, 1, productIds.get(2), "T83-P3-" + suffix,
                "Producto 3", "5.000", 3000L, 15000L);
        jdbc.sql("UPDATE sale_lines SET returned_quantity = 1.000 WHERE id = :id").param("id", sale2Line).update();
        insertPayment(sale2, "DEBIT", 10000L, null, "APPROVED");
        insertPayment(sale2, "CASH", 5000L, 5000L, "APPROVED");

        long partialReturn = insertReturn(sale2, storeA, terminalA1, closedSessionA, "RETURN", 3000L);
        insertReturnLine(partialReturn, sale2Line, "1.000", 3000L);
        insertReturnRefund(partialReturn, "DEBIT", 3000L);

        // Venta 3 (15:20): P4..P12 total = 61000 (TRANSFER 41000 + CREDIT 20000)
        // P4=11000, P5=10000, P6=9000, P7=8000, P8=7000, P9=6000, P10=5000, P11=3000, P12=2000
        long sale3 = insertSale(storeA, terminalA1, closedSessionA, managerId,
                TARGET_DATE, LocalTime.of(15, 20), 61000L, "COMPLETED");
        long[] amounts = {11000L, 10000L, 9000L, 8000L, 7000L, 6000L, 5000L, 3000L, 2000L};
        for (int idx = 0; idx < amounts.length; idx++) {
            int pIndex = idx + 3; // P4..P12
            insertSaleLine(sale3, storeA, TARGET_DATE, idx + 1, productIds.get(pIndex),
                    "T83-P" + (pIndex + 1) + "-" + suffix,
                    "Producto " + (pIndex + 1), "1.000", amounts[idx], amounts[idx]);
        }
        insertPayment(sale3, "TRANSFER", 41000L, null, "APPROVED");
        insertPayment(sale3, "CREDIT", 20000L, null, "APPROVED");

        // Venta 4 (15:50): VOIDED (50000) -> debe quedar totalmente excluida sin descontarse dos veces
        long voidedSale = insertSale(storeA, terminalA1, closedSessionA, managerId,
                TARGET_DATE, LocalTime.of(15, 50), 50000L, "VOIDED");
        long voidedLine = insertSaleLine(voidedSale, storeA, TARGET_DATE, 1, productIds.get(11),
                "T83-P12-" + suffix, "Producto 12", "5.000", 10000L, 50000L);
        insertPayment(voidedSale, "CASH", 50000L, 50000L, "REVERSED");
        long voidReturn = insertReturn(voidedSale, storeA, terminalA1, closedSessionA, "VOID", 50000L);
        insertReturnLine(voidReturn, voidedLine, "5.000", 50000L);
        insertReturnRefund(voidReturn, "CASH", 50000L);

        // Venta en storeB en TARGET_DATE (12000): aislada cuando se consulta solo storeA,
        // incluida cuando el admin solicita storeIds=[storeA, storeB].
        long saleB = insertSale(storeB, terminalB1, closedSessionB, approverId,
                TARGET_DATE, LocalTime.of(12, 0), 12000L, "COMPLETED");
        insertSaleLine(saleB, storeB, TARGET_DATE, 1, productIds.get(0), "T83-P1-" + suffix,
                "Producto 1", "1.000", 12000L, 12000L);
        insertPayment(saleB, "CASH", 12000L, 12000L, "APPROVED");
    }

    private void seedAlertsInStoreA() {
        LocalDate today = businessClock.businessDate();

        // 1) Stock: belowMin = 2 (P1 qty=3 min=5, P2 qty=0 min=5); zeroStock = 2 (P2 qty=0, P3 qty=0 min=null)
        insertStock(storeA, productIds.get(0), "3.000", "5.000", "20.000");
        insertStock(storeA, productIds.get(1), "0.000", "5.000", "20.000");
        insertStock(storeA, productIds.get(2), "0.000", null, null);
        insertStock(storeA, productIds.get(3), "10.000", "5.000", "20.000");
        // Stock en storeB no cuenta para storeA
        insertStock(storeB, productIds.get(0), "0.000", "5.000", "20.000");

        // 2) Lotes por vencer con override de tienda = 10 días (menor al global 15)
        upsertStoreNumberSetting(storeA, "alerts.expiry.days_ahead", "10");
        insertLot(storeA, productIds.get(0), "L-EXP-" + suffix, today.minusDays(2), "2.000", "ACTIVE");
        insertLot(storeA, productIds.get(1), "L-5D-" + suffix, today.plusDays(5), "3.000", "ACTIVE");
        insertLot(storeA, productIds.get(2), "L-12D-" + suffix, today.plusDays(12), "4.000", "ACTIVE"); // fuera de 10d
        insertLot(storeA, productIds.get(3), "L-ZERO-" + suffix, today.plusDays(1), "0.000", "DEPLETED"); // saldo 0

        // 3) Mermas: 2 PENDING_APPROVAL, 1 APPROVED, 1 REJECTED -> cuenta 2
        insertShrinkage(storeA, productIds.get(0), "PENDING_APPROVAL", null, false);
        insertShrinkage(storeA, productIds.get(1), "PENDING_APPROVAL", null, false);
        insertShrinkage(storeA, productIds.get(2), "APPROVED", approverId, false);
        insertShrinkage(storeA, productIds.get(3), "REJECTED", approverId, false);

        // 4) Ajustes manuales: 1 PENDING_APPROVAL, 1 APPROVED -> cuenta 1
        insertAdjustment(storeA, "PENDING_APPROVAL", null);
        insertAdjustment(storeA, "APPROVED", approverId);

        // 5) Tomas de inventario: 1 SUBMITTED, 1 OPEN, 1 APPROVED -> cuenta 1 (solo SUBMITTED)
        insertInventoryCount(storeA, "FULL", null, "SUBMITTED");
        insertInventoryCount(storeA, "CYCLIC", categoryMain, "OPEN");
        insertInventoryCount(storeA, "CYCLIC", categoryOther, "APPROVED");

        // 6) Diferencias de caja con tolerancia de tienda = 300 (menor al global 500):
        //    -400 sin aprobar -> cuenta 1; 200 sin aprobar -> no; -2000 aprobada -> no; OPEN -> no
        upsertStoreNumberSetting(storeA, "cash.difference_tolerance", "300");
        insertClosedSession(storeA, terminalA1, managerId, today, "-400", null);
        insertClosedSession(storeA, terminalA1, managerId, today, "200", null);
        insertClosedSession(storeA, terminalA1, managerId, today, "-2000", approverId);
        insertOpenSession(storeA, terminalA2, openSessionCashierId, today);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers de autenticación e inserción
    // -----------------------------------------------------------------------------------------

    private static RequestPostProcessor dashboardManagerAuth(long userId) {
        return user(String.valueOf(userId)).authorities(
                new SimpleGrantedAuthority(Permissions.REPORT_DASHBOARD_READ));
    }

    private static RequestPostProcessor adminAuth(long userId) {
        return user(String.valueOf(userId)).authorities(
                new SimpleGrantedAuthority(Permissions.REPORT_DASHBOARD_READ),
                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS));
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

    private void assignRole(long userId, String roleCode) {
        int inserted = jdbc.sql("""
                        INSERT INTO user_roles (user_id, role_id)
                        SELECT :u, r.id
                        FROM roles r
                        WHERE r.code = :code
                        ON CONFLICT (user_id, role_id) DO NOTHING
                        """)
                .param("u", userId)
                .param("code", roleCode)
                .update();
        if (inserted == 0) {
            throw new IllegalStateException("No se encontró el rol semilla: " + roleCode);
        }
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

    private long insertClosedSession(
            long storeId,
            long terminalId,
            long cashierId,
            LocalDate date,
            String differenceTotal,
            Long approvedBy) {
        BigDecimal opening = new BigDecimal("50000");
        BigDecimal diff = new BigDecimal(differenceTotal);
        BigDecimal counted = opening.add(diff);
        OffsetDateTime approvedAt = approvedBy != null ? OffsetDateTime.now(ZoneOffset.UTC) : null;
        return jdbc.sql("""
                        INSERT INTO cash_sessions (
                            number, store_id, terminal_id, cashier_user_id, status, business_date,
                            opening_amount, closed_at, closed_by, expected_total, counted_total,
                            difference_total, difference_approved_by, difference_approved_at
                        )
                        VALUES (
                            :num, :s, :t, :c, 'CLOSED', :d,
                            :opening, now(), :c, :opening, :counted,
                            :diff, :appBy, :appAt
                        )
                        RETURNING id
                        """)
                .param("num", "CS-" + suffix + "-" + (++seq))
                .param("s", storeId)
                .param("t", terminalId)
                .param("c", cashierId)
                .param("d", date)
                .param("opening", opening)
                .param("counted", counted)
                .param("diff", diff)
                .param("appBy", approvedBy, Types.BIGINT)
                .param("appAt", approvedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class)
                .single();
    }

    private void insertOpenSession(long storeId, long terminalId, long cashierId, LocalDate date) {
        jdbc.sql("""
                        INSERT INTO cash_sessions (
                            number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount
                        )
                        VALUES (:num, :s, :t, :c, 'OPEN', :d, 50000)
                        """)
                .param("num", "CSO-" + suffix + "-" + (++seq))
                .param("s", storeId)
                .param("t", terminalId)
                .param("c", cashierId)
                .param("d", date)
                .update();
    }

    private long insertCategory(String name) {
        return jdbc.sql("INSERT INTO categories (name, active) VALUES (:n, true) RETURNING id")
                .param("n", name)
                .query(Long.class)
                .single();
    }

    private long insertProduct(String sku, String name, long categoryId, long priceGross, String avgCost) {
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

    private long insertSale(
            long storeId,
            long terminalId,
            long cashSessionId,
            long cashierId,
            LocalDate businessDate,
            LocalTime localTime,
            long totalAmount,
            String status) {
        BigDecimal total = BigDecimal.valueOf(totalAmount);
        BigDecimal net = Rounding.clp(total.divide(IVA_DIVISOR, Rounding.CLP_SCALE, RoundingMode.HALF_UP));
        BigDecimal tax = total.subtract(net);
        return jdbc.sql("""
                        INSERT INTO sales (
                            number, store_id, terminal_id, cash_session_id, cashier_user_id, status,
                            sold_at, business_date, idempotency_key,
                            subtotal_gross, discount_total, total_gross, net_total, tax_total, exempt_total,
                            rounding_adjustment, total_payable, paid_total, change_total
                        )
                        VALUES (
                            :num, :s, :t, :cs, :u, :status,
                            :soldAt, :d, :idem,
                            :total, 0, :total, :net, :tax, 0,
                            0, :total, :total, 0
                        )
                        RETURNING id
                        """)
                .param("num", "V83-" + suffix + "-" + (++seq))
                .param("s", storeId)
                .param("t", terminalId)
                .param("cs", cashSessionId)
                .param("u", cashierId)
                .param("status", status)
                .param("soldAt", utcAt(businessDate, localTime))
                .param("d", businessDate)
                .param("idem", UUID.randomUUID())
                .param("total", total)
                .param("net", net)
                .param("tax", tax)
                .query(Long.class)
                .single();
    }

    private long insertSaleLine(
            long saleId,
            long storeId,
            LocalDate businessDate,
            int lineNumber,
            long productId,
            String sku,
            String name,
            String qty,
            long unitPrice,
            long lineTotal) {
        BigDecimal total = BigDecimal.valueOf(lineTotal);
        BigDecimal net = Rounding.clp(total.divide(IVA_DIVISOR, Rounding.CLP_SCALE, RoundingMode.HALF_UP));
        BigDecimal tax = total.subtract(net);
        return jdbc.sql("""
                        INSERT INTO sale_lines (
                            sale_id, line_number, store_id, business_date, product_id, sku, product_name,
                            sale_type, quantity, stock_product_id, stock_quantity,
                            unit_price_gross, line_subtotal_gross, discount_amount, line_total_gross,
                            line_net, line_tax, line_exempt, unit_cost, line_cost, restricted
                        )
                        VALUES (
                            :saleId, :ln, :s, :d, :p, :sku, :name,
                            'UNIT', :qty, :p, :qty,
                            :price, :total, 0, :total,
                            :net, :tax, 0, 500.0000, 500.00, false
                        )
                        RETURNING id
                        """)
                .param("saleId", saleId)
                .param("ln", lineNumber)
                .param("s", storeId)
                .param("d", businessDate)
                .param("p", productId)
                .param("sku", sku)
                .param("name", name)
                .param("qty", new BigDecimal(qty))
                .param("price", unitPrice)
                .param("total", total)
                .param("net", net)
                .param("tax", tax)
                .query(Long.class)
                .single();
    }

    private void insertPayment(long saleId, String method, long amount, Long tenderedAmount, String status) {
        jdbc.sql("""
                        INSERT INTO sale_payments (
                            sale_id, method, amount, tendered_amount, change_amount, status
                        )
                        VALUES (:saleId, :method, :amount, :tendered, 0, :status)
                        """)
                .param("saleId", saleId)
                .param("method", method)
                .param("amount", amount)
                .param("tendered", tenderedAmount, Types.NUMERIC)
                .param("status", status)
                .update();
    }

    private long insertReturn(
            long saleId,
            long storeId,
            long terminalId,
            long cashSessionId,
            String returnType,
            long refundTotal) {
        return jdbc.sql("""
                        INSERT INTO sale_returns (
                            number, sale_id, return_type, store_id, terminal_id, cash_session_id,
                            reason_code, refund_total, idempotency_key, created_by
                        )
                        VALUES (:num, :saleId, :type, :s, :t, :cs, 'CUSTOMER_REQUEST', :refund, :idem, :u)
                        RETURNING id
                        """)
                .param("num", "D83-" + suffix + "-" + (++seq))
                .param("saleId", saleId)
                .param("type", returnType)
                .param("s", storeId)
                .param("t", terminalId)
                .param("cs", cashSessionId)
                .param("refund", refundTotal)
                .param("idem", UUID.randomUUID())
                .param("u", managerId)
                .query(Long.class)
                .single();
    }

    private void insertReturnLine(long saleReturnId, long saleLineId, String qty, long refundAmount) {
        BigDecimal total = BigDecimal.valueOf(refundAmount);
        BigDecimal net = Rounding.clp(total.divide(IVA_DIVISOR, Rounding.CLP_SCALE, RoundingMode.HALF_UP));
        BigDecimal tax = total.subtract(net);
        jdbc.sql("""
                        INSERT INTO sale_return_lines (
                            sale_return_id, sale_line_id, quantity, stock_quantity, restock,
                            refund_amount, refund_net, refund_tax
                        )
                        VALUES (:retId, :lineId, :qty, :qty, true, :amount, :net, :tax)
                        """)
                .param("retId", saleReturnId)
                .param("lineId", saleLineId)
                .param("qty", new BigDecimal(qty))
                .param("amount", total)
                .param("net", net)
                .param("tax", tax)
                .update();
    }

    private void insertReturnRefund(long saleReturnId, String method, long amount) {
        jdbc.sql("""
                        INSERT INTO sale_return_refunds (sale_return_id, method, amount)
                        VALUES (:retId, :method, :amount)
                        """)
                .param("retId", saleReturnId)
                .param("method", method)
                .param("amount", amount)
                .update();
    }

    private void insertStock(long storeId, long productId, String qty, String minQty, String maxQty) {
        jdbc.sql("""
                        INSERT INTO stock (store_id, product_id, quantity, min_qty, max_qty)
                        VALUES (:s, :p, :q, :min, :max)
                        """)
                .param("s", storeId)
                .param("p", productId)
                .param("q", new BigDecimal(qty))
                .param("min", minQty != null ? new BigDecimal(minQty) : null, Types.NUMERIC)
                .param("max", maxQty != null ? new BigDecimal(maxQty) : null, Types.NUMERIC)
                .update();
    }

    private void insertLot(long storeId, long productId, String lotNumber, LocalDate expiry, String qty, String status) {
        jdbc.sql("""
                        INSERT INTO lots (store_id, product_id, lot_number, expiry_date, quantity, unit_cost, received_at, status)
                        VALUES (:s, :p, :ln, :exp, :q, 500.0000, now(), :status)
                        """)
                .param("s", storeId)
                .param("p", productId)
                .param("ln", lotNumber)
                .param("exp", expiry)
                .param("q", new BigDecimal(qty))
                .param("status", status)
                .update();
    }

    private void insertShrinkage(long storeId, long productId, String status, Long approvedBy, boolean autoApproved) {
        OffsetDateTime approvedAt = approvedBy != null ? OffsetDateTime.now(ZoneOffset.UTC) : null;
        jdbc.sql("""
                        INSERT INTO shrinkages (
                            number, store_id, product_id, shrinkage_type, quantity, unit_cost, total_cost,
                            responsible_user_id, reported_by, status, approved_by, approved_at,
                            auto_approved, affects_stock, occurred_at
                        )
                        VALUES (
                            :num, :s, :p, 'DAMAGED', 1.000, 500.0000, 500.00,
                            :rep, :rep, :status, :app, :appAt,
                            :auto, true, now()
                        )
                        """)
                .param("num", "MR83-" + suffix + "-" + (++seq))
                .param("s", storeId)
                .param("p", productId)
                .param("rep", managerId)
                .param("status", status)
                .param("app", approvedBy, Types.BIGINT)
                .param("appAt", approvedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("auto", autoApproved)
                .update();
    }

    private void insertAdjustment(long storeId, String status, Long approvedBy) {
        OffsetDateTime approvedAt = approvedBy != null ? OffsetDateTime.now(ZoneOffset.UTC) : null;
        jdbc.sql("""
                        INSERT INTO stock_adjustments (
                            number, store_id, reason_code, status, total_cost_impact,
                            requested_by, approved_by, approved_at
                        )
                        VALUES (
                            :num, :s, 'COUNT_CORRECTION', :status, 1000.00,
                            :req, :app, :appAt
                        )
                        """)
                .param("num", "AJ83-" + suffix + "-" + (++seq))
                .param("s", storeId)
                .param("status", status)
                .param("req", managerId)
                .param("app", approvedBy, Types.BIGINT)
                .param("appAt", approvedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private void insertInventoryCount(long storeId, String countType, Long categoryId, String status) {
        boolean isSubmittedOrApproved = "SUBMITTED".equals(status) || "APPROVED".equals(status);
        boolean isApproved = "APPROVED".equals(status);
        Long submittedBy = isSubmittedOrApproved ? managerId : null;
        OffsetDateTime submittedAt = isSubmittedOrApproved ? OffsetDateTime.now(ZoneOffset.UTC) : null;
        Long approvedBy = isApproved ? approverId : null;
        OffsetDateTime approvedAt = isApproved ? OffsetDateTime.now(ZoneOffset.UTC) : null;

        jdbc.sql("""
                        INSERT INTO inventory_counts (
                            number, store_id, count_type, category_id, status, blind, started_by,
                            submitted_by, submitted_at, approved_by, approved_at
                        )
                        VALUES (
                            :num, :s, :type, :cat, :status, true, :u,
                            :subBy, :subAt, :appBy, :appAt
                        )
                        """)
                .param("num", "TI83-" + suffix + "-" + (++seq))
                .param("s", storeId)
                .param("type", countType)
                .param("cat", categoryId, Types.BIGINT)
                .param("status", status)
                .param("u", managerId)
                .param("subBy", submittedBy, Types.BIGINT)
                .param("subAt", submittedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("appBy", approvedBy, Types.BIGINT)
                .param("appAt", approvedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private void upsertStoreNumberSetting(long storeId, String key, String jsonNumber) {
        jdbc.sql("""
                        INSERT INTO settings (scope, store_id, key, value, value_type, description)
                        VALUES ('STORE', :s, :key, CAST(:val AS jsonb), 'NUMBER', 'Override test T83')
                        ON CONFLICT (key, store_id) WHERE store_id IS NOT NULL
                        DO UPDATE SET value = EXCLUDED.value
                        """)
                .param("s", storeId)
                .param("key", key)
                .param("val", jsonNumber)
                .update();
    }

    private OffsetDateTime utcAt(LocalDate day, LocalTime time) {
        return OffsetDateTime.ofInstant(
                businessClock.startOfDay(day).plusSeconds(time.toSecondOfDay()), ZoneOffset.UTC);
    }
}
