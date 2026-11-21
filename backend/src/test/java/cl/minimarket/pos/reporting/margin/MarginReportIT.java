package cl.minimarket.pos.reporting.margin;

import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del reporte de margen y utilidad ({@code T85}, {@code HU-REP-04}).
 *
 * <p>Verifica con datos fijos que {@code grossMargin = netSales − cost} (snapshot de línea),
 * descuento de devoluciones y anulaciones, tratamiento de productos exentos de IVA, las 4
 * agrupaciones ({@code product}, {@code category}, {@code brand}, {@code day}), soporte
 * multi-local {@code storeIds[]} y reglas de autorización/validación.
 */
class MarginReportIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate DAY_1 = LocalDate.of(2031, 3, 10);
    private static final LocalDate DAY_2 = LocalDate.of(2031, 3, 11);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private String sfx;
    private long storeA;
    private long storeB;
    private long userId;
    private long restrictedUserId;
    private long categoryA;
    private long categoryB;
    private long brandA;
    private long product1;
    private long product2;
    private int seq;

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeA = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeB = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        userId = TestData.insertUser(jdbc);
        restrictedUserId = TestData.insertUser(jdbc);
        jdbc.sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:u, :s, true)")
                .param("u", restrictedUserId)
                .param("s", storeA)
                .update();

        long termA = insertTerminal(storeA, "TM1-" + sfx);
        long termB = insertTerminal(storeB, "TM2-" + sfx);
        long sessA = insertClosedSession(storeA, termA, DAY_1);
        long sessB = insertClosedSession(storeB, termB, DAY_1);

        categoryA = insertCategory("Bebidas M85 " + sfx);
        categoryB = insertCategory("Panadería Exenta M85 " + sfx);
        brandA = insertBrand("Marca M85 " + sfx);

        product1 = insertProduct("M85-P1-" + sfx, "Bebida M85 " + sfx, categoryA, brandA, 1190, "600.0000", false);
        product2 = insertProduct("M85-P2-" + sfx, "Pan Exento M85 " + sfx, categoryB, null, 500, "350.0000", true);

        // Local A, Día 1: venta de 5 unidades de P1 (neto 5000, costo 3000) con devolución de 2 (neto 2000)
        // Resultado neto P1 en storeA: qty=3, netSales=3000, cost=1800.00, grossMargin=1200.00, marginPct=40.00
        long sale1 = insertSale(storeA, termA, sessA, DAY_1, 5950, 5000, 950, 0, "PARTIALLY_RETURNED");
        long line1 = insertSaleLine(sale1, storeA, DAY_1, product1, "M85-P1-" + sfx, "Bebida M85 " + sfx,
                "5.000", 1190, 5950, 5000, 950, 0, "600.0000", "3000.00", "2.000");
        long ret1 = insertReturn(sale1, "RETURN", storeA, termA, sessA, 2380);
        insertReturnLine(ret1, line1, "2.000", 2380, 2000, 380);

        // Local A, Día 1: venta anulada (VOID) de P1 -> efecto neto 0
        long saleVoid = insertSale(storeA, termA, sessA, DAY_1, 4760, 4000, 760, 0, "VOIDED");
        long lineVoid = insertSaleLine(saleVoid, storeA, DAY_1, product1, "M85-P1-" + sfx, "Bebida M85 " + sfx,
                "4.000", 1190, 4760, 4000, 760, 0, "600.0000", "2400.00", "4.000");
        long voidRet = insertReturn(saleVoid, "VOID", storeA, termA, sessA, 4760);
        insertReturnLine(voidRet, lineVoid, "4.000", 4760, 4000, 760);

        // Local A, Día 2: venta de 2 unidades de P2 exento de IVA (exempt 1000, costo 700)
        // Resultado neto P2 en storeA: qty=2, netSales=1000, cost=700.00, grossMargin=300.00, marginPct=30.00
        long sale2 = insertSale(storeA, termA, sessA, DAY_2, 1000, 0, 0, 1000, "COMPLETED");
        insertSaleLine(sale2, storeA, DAY_2, product2, "M85-P2-" + sfx, "Pan Exento M85 " + sfx,
                "2.000", 500, 1000, 0, 0, 1000, "350.0000", "700.00", "0.000");

        // Local B, Día 1: venta de 1 unidad de P1 (neto 1000, costo 600)
        long saleB = insertSale(storeB, termB, sessB, DAY_1, 1190, 1000, 190, 0, "COMPLETED");
        insertSaleLine(saleB, storeB, DAY_1, product1, "M85-P1-" + sfx, "Bebida M85 " + sfx,
                "1.000", 1190, 1190, 1000, 190, 0, "600.0000", "600.00", "0.000");
    }

    @Test
    void marginReportComputesNetMinusCostAndSupportsAllGroupingsAndMultiStore() throws Exception {
        // 1. groupBy=product en storeA
        MvcResult prodRes = mvc.perform(get("/api/v1/reports/margin")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("groupBy", "product")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_MARGIN_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.storeIds[0]").value(storeA))
                .andReturn();

        JsonNode prodRoot = JSON.readTree(prodRes.getResponse().getContentAsString());
        Map<String, JsonNode> byProduct = rowsByKey(prodRoot.get("rows"));
        assertThat(byProduct).containsOnlyKeys(String.valueOf(product1), String.valueOf(product2));

        JsonNode p1Row = byProduct.get(String.valueOf(product1));
        assertThat(p1Row.get("qty").decimalValue()).isEqualByComparingTo("3.000");
        assertThat(p1Row.get("netSales").decimalValue()).isEqualByComparingTo("3000");
        assertThat(p1Row.get("cost").decimalValue()).isEqualByComparingTo("1800.00");
        assertThat(p1Row.get("grossMargin").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(p1Row.get("marginPct").decimalValue()).isEqualByComparingTo("40.00");

        JsonNode p2Row = byProduct.get(String.valueOf(product2));
        assertThat(p2Row.get("qty").decimalValue()).isEqualByComparingTo("2.000");
        assertThat(p2Row.get("netSales").decimalValue()).isEqualByComparingTo("1000");
        assertThat(p2Row.get("cost").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(p2Row.get("grossMargin").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(p2Row.get("marginPct").decimalValue()).isEqualByComparingTo("30.00");

        JsonNode totals = prodRoot.get("totals");
        assertThat(totals.get("qty").decimalValue()).isEqualByComparingTo("5.000");
        assertThat(totals.get("netSales").decimalValue()).isEqualByComparingTo("4000");
        assertThat(totals.get("cost").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(totals.get("grossMargin").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(totals.get("marginPct").decimalValue()).isEqualByComparingTo("37.50");

        // 2. groupBy=category en storeA
        MvcResult catRes = mvc.perform(get("/api/v1/reports/margin")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("groupBy", "category")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_MARGIN_READ))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, JsonNode> byCat = rowsByKey(JSON.readTree(catRes.getResponse().getContentAsString()).get("rows"));
        assertThat(byCat).containsOnlyKeys(String.valueOf(categoryA), String.valueOf(categoryB));
        assertThat(byCat.get(String.valueOf(categoryA)).get("grossMargin").decimalValue())
                .isEqualByComparingTo("1200.00");

        // 3. groupBy=brand en storeA (incluye productos sin marca bajo NONE)
        MvcResult brandRes = mvc.perform(get("/api/v1/reports/margin")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("groupBy", "brand")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_MARGIN_READ))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, JsonNode> byBrand = rowsByKey(JSON.readTree(brandRes.getResponse().getContentAsString()).get("rows"));
        assertThat(byBrand).containsOnlyKeys(String.valueOf(brandA), "NONE");
        assertThat(byBrand.get("NONE").get("label").asString()).isEqualTo("Sin marca");
        assertThat(byBrand.get("NONE").get("grossMargin").decimalValue()).isEqualByComparingTo("300.00");

        // 4. groupBy=day en storeA (ordenado cronológicamente)
        MvcResult dayRes = mvc.perform(get("/api/v1/reports/margin")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("groupBy", "day")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_MARGIN_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].key").value(DAY_1.toString()))
                .andExpect(jsonPath("$.rows[1].key").value(DAY_2.toString()))
                .andReturn();
        JsonNode dayRoot = JSON.readTree(dayRes.getResponse().getContentAsString());
        assertThat(dayRoot.get("rows").get(0).get("grossMargin").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(dayRoot.get("rows").get(1).get("grossMargin").decimalValue()).isEqualByComparingTo("300.00");

        // 5. Multi-local storeIds=storeA,storeB
        MvcResult multiRes = mvc.perform(get("/api/v1/reports/margin")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("storeIds", storeA + "," + storeB)
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_MARGIN_READ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode multiTotals = JSON.readTree(multiRes.getResponse().getContentAsString()).get("totals");
        assertThat(multiTotals.get("qty").decimalValue()).isEqualByComparingTo("6.000");
        assertThat(multiTotals.get("netSales").decimalValue()).isEqualByComparingTo("5000");
        assertThat(multiTotals.get("cost").decimalValue()).isEqualByComparingTo("3100.00");
        assertThat(multiTotals.get("grossMargin").decimalValue()).isEqualByComparingTo("1900.00");
    }

    @Test
    void rejectsUnauthorizedUnassignedStoreAndInvalidFilters() throws Exception {
        // Sin permiso report:margin:read -> 403 ACCESS_DENIED
        mvc.perform(get("/api/v1/reports/margin")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SALES_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Local en storeIds[] no asignado al usuario -> 403 STORE_ACCESS_DENIED
        mvc.perform(get("/api/v1/reports/margin")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("storeIds", storeA + "," + storeB)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(restrictedUserId))
                                .authorities(new SimpleGrantedAuthority(Permissions.REPORT_MARGIN_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

        // Sin X-Store-Id -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/reports/margin")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_MARGIN_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Sin fechas o groupBy inválido -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/reports/margin")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_MARGIN_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/reports/margin")
                        .param("from", DAY_1.toString())
                        .param("to", DAY_2.toString())
                        .param("groupBy", "invalid")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_MARGIN_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static Map<String, JsonNode> rowsByKey(JsonNode rows) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            map.put(row.get("key").asString(), row);
        }
        return map;
    }

    private long insertTerminal(long storeId, String code) {
        return jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:s, :c, :n, true)
                        RETURNING id
                        """)
                .param("s", storeId).param("c", code).param("n", "Caja " + code)
                .query(Long.class).single();
    }

    private long insertClosedSession(long storeId, long terminalId, LocalDate businessDate) {
        return jdbc.sql("""
                        INSERT INTO cash_sessions (
                            number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount
                        )
                        VALUES (:num, :s, :t, :u, 'CLOSED', :d, 50000)
                        RETURNING id
                        """)
                .param("num", "M85T-" + sfx + "-" + (++seq))
                .param("s", storeId).param("t", terminalId).param("u", userId).param("d", businessDate)
                .query(Long.class).single();
    }

    private long insertCategory(String name) {
        return jdbc.sql("INSERT INTO categories (name, active) VALUES (:n, true) RETURNING id")
                .param("n", name).query(Long.class).single();
    }

    private long insertBrand(String name) {
        return jdbc.sql("INSERT INTO brands (name, active) VALUES (:n, true) RETURNING id")
                .param("n", name).query(Long.class).single();
    }

    private long insertProduct(String sku, String name, long categoryId, Long brandId,
                               long priceGross, String avgCost, boolean vatExempt) {
        return jdbc.sql("""
                        INSERT INTO products (
                            sku, name, category_id, brand_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, lot_controlled, active
                        )
                        VALUES (:sku, :name, :cat, :brand, 'UNIT', 'UNIT', :price, :cost, :exempt, false, true)
                        RETURNING id
                        """)
                .param("sku", sku).param("name", name).param("cat", categoryId).param("brand", brandId, Types.BIGINT)
                .param("price", priceGross).param("cost", new BigDecimal(avgCost)).param("exempt", vatExempt)
                .query(Long.class).single();
    }

    private long insertSale(long storeId, long terminalId, long sessionId, LocalDate date,
                            long gross, long net, long tax, long exempt, String status) {
        return jdbc.sql("""
                        INSERT INTO sales (
                            number, store_id, terminal_id, cash_session_id, cashier_user_id, status,
                            sold_at, business_date, idempotency_key,
                            subtotal_gross, discount_total, total_gross, net_total, tax_total, exempt_total,
                            rounding_adjustment, total_payable, paid_total, change_total
                        )
                        VALUES (
                            :num, :s, :t, :cs, :u, :st,
                            :soldAt, :d, :idem,
                            :gross, 0, :gross, :net, :tax, :exempt,
                            0, :gross, :gross, 0
                        )
                        RETURNING id
                        """)
                .param("num", "M85V-" + sfx + "-" + (++seq))
                .param("s", storeId).param("t", terminalId).param("cs", sessionId)
                .param("u", userId).param("st", status)
                .param("soldAt", OffsetDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                        16, 0, 0, 0, ZoneOffset.UTC))
                .param("d", date).param("idem", UUID.randomUUID())
                .param("gross", gross).param("net", net).param("tax", tax).param("exempt", exempt)
                .query(Long.class).single();
    }

    private long insertSaleLine(long saleId, long storeId, LocalDate date, long productId, String sku,
                                String productName, String qty, long unitPrice, long gross, long net,
                                long tax, long exempt, String unitCost, String lineCost, String returnedQty) {
        return jdbc.sql("""
                        INSERT INTO sale_lines (
                            sale_id, line_number, store_id, business_date, product_id, sku, product_name,
                            sale_type, quantity, stock_product_id, stock_quantity,
                            unit_price_gross, line_subtotal_gross, discount_amount, line_total_gross,
                            line_net, line_tax, line_exempt, unit_cost, line_cost, restricted, returned_quantity
                        )
                        VALUES (
                            :saleId, 1, :s, :d, :p, :sku, :pname,
                            'UNIT', :qty, :p, :qty,
                            :unitPrice, :gross, 0, :gross,
                            :net, :tax, :exempt, :unitCost, :lineCost, false, :retQty
                        )
                        RETURNING id
                        """)
                .param("saleId", saleId).param("s", storeId).param("d", date)
                .param("p", productId).param("sku", sku).param("pname", productName)
                .param("qty", new BigDecimal(qty)).param("unitPrice", unitPrice)
                .param("gross", gross).param("net", net).param("tax", tax).param("exempt", exempt)
                .param("unitCost", new BigDecimal(unitCost)).param("lineCost", new BigDecimal(lineCost))
                .param("retQty", new BigDecimal(returnedQty))
                .query(Long.class).single();
    }

    private long insertReturn(long saleId, String returnType, long storeId, long terminalId,
                              long sessionId, long refundTotal) {
        return jdbc.sql("""
                        INSERT INTO sale_returns (
                            number, sale_id, return_type, store_id, terminal_id, cash_session_id,
                            reason_code, refund_total, idempotency_key, created_by, created_at
                        )
                        VALUES (
                            :num, :saleId, :type, :s, :t, :cs,
                            'CUSTOMER_REQUEST', :refund, :idem, :u, now()
                        )
                        RETURNING id
                        """)
                .param("num", "M85D-" + sfx + "-" + (++seq))
                .param("saleId", saleId).param("type", returnType)
                .param("s", storeId).param("t", terminalId).param("cs", sessionId)
                .param("refund", refundTotal).param("idem", UUID.randomUUID()).param("u", userId)
                .query(Long.class).single();
    }

    private void insertReturnLine(long returnId, long saleLineId, String qty,
                                  long refundAmount, long refundNet, long refundTax) {
        jdbc.sql("""
                        INSERT INTO sale_return_lines (
                            sale_return_id, sale_line_id, quantity, stock_quantity, restock,
                            refund_amount, refund_net, refund_tax
                        )
                        VALUES (:retId, :lineId, :qty, :qty, true, :amount, :net, :tax)
                        """)
                .param("retId", returnId).param("lineId", saleLineId)
                .param("qty", new BigDecimal(qty))
                .param("amount", refundAmount).param("net", refundNet).param("tax", refundTax)
                .update();
    }
}
