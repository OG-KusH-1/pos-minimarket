package cl.minimarket.pos.reporting.shrinkage;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * Pruebas de integración de los reportes de merma ({@code T85}, {@code HU-MER-04},
 * {@code HU-MER-05}).
 *
 * <p>Verifica el criterio de terminado con datos fijos:
 * <ul>
 *   <li>Solo cuentan las mermas {@code APPROVED} (excluye {@code PENDING_APPROVAL} y
 *       {@code REJECTED}).</li>
 *   <li>{@code pctOfSales = 1.00} para merma aprobada total de {@code $10.000} sobre ventas netas
 *       de {@code $1.000.000}.</li>
 *   <li>Agrupaciones {@code type}, {@code product}, {@code category}, {@code store}, {@code month}
 *       y ranking {@code /reports/shrinkage/top-products?limit=}.</li>
 * </ul>
 */
class ShrinkageReportIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate FROM = LocalDate.of(2031, 5, 1);
    private static final LocalDate TO = LocalDate.of(2031, 5, 31);
    private static final LocalDate SALE_DAY = LocalDate.of(2031, 5, 10);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BusinessClock clock;

    private String sfx;
    private long storeA;
    private long storeB;
    private long reporterId;
    private long approverId;
    private long restrictedUserId;
    private long catA;
    private long catB;
    private long prod1;
    private long prod2;
    private long prod3;
    private int seq;

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeA = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeB = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        reporterId = TestData.insertUser(jdbc);
        approverId = TestData.insertUser(jdbc);
        restrictedUserId = TestData.insertUser(jdbc);
        jdbc.sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:u, :s, true)")
                .param("u", restrictedUserId)
                .param("s", storeA)
                .update();

        long termA = jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:s, :c, 'Caja SH85', true) RETURNING id
                        """)
                .param("s", storeA).param("c", "TS-" + sfx).query(Long.class).single();
        long sessA = jdbc.sql("""
                        INSERT INTO cash_sessions (
                            number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount
                        )
                        VALUES (:num, :s, :t, :u, 'CLOSED', :d, 50000) RETURNING id
                        """)
                .param("num", "S85C-" + sfx).param("s", storeA).param("t", termA)
                .param("u", reporterId).param("d", SALE_DAY).query(Long.class).single();

        catA = insertCategory("Lácteos SH85 " + sfx);
        catB = insertCategory("Bebidas SH85 " + sfx);
        prod1 = insertProduct("SH85-P1-" + sfx, "Yogurt SH85 " + sfx, catA, "1200.0000");
        prod2 = insertProduct("SH85-P2-" + sfx, "Jugo SH85 " + sfx, catB, "2000.0000");
        prod3 = insertProduct("SH85-P3-" + sfx, "Agua SH85 " + sfx, catB, "1000.0000");

        // Ventas netas en storeA = $1.000.000 (bruto $1.190.000, IVA $190.000)
        long saleId = jdbc.sql("""
                        INSERT INTO sales (
                            number, store_id, terminal_id, cash_session_id, cashier_user_id, status,
                            sold_at, business_date, idempotency_key,
                            subtotal_gross, discount_total, total_gross, net_total, tax_total, exempt_total,
                            rounding_adjustment, total_payable, paid_total, change_total
                        )
                        VALUES (
                            :num, :s, :t, :cs, :u, 'COMPLETED',
                            :soldAt, :d, :idem,
                            1190000, 0, 1190000, 1000000, 190000, 0,
                            0, 1190000, 1190000, 0
                        )
                        RETURNING id
                        """)
                .param("num", "S85V-" + sfx).param("s", storeA).param("t", termA)
                .param("cs", sessA).param("u", reporterId)
                .param("soldAt", utcAt(SALE_DAY, LocalTime.NOON))
                .param("d", SALE_DAY).param("idem", UUID.randomUUID())
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO sale_lines (
                            sale_id, line_number, store_id, business_date, product_id, sku, product_name,
                            sale_type, quantity, stock_product_id, stock_quantity,
                            unit_price_gross, line_subtotal_gross, discount_amount, line_total_gross,
                            line_net, line_tax, line_exempt, unit_cost, line_cost, restricted
                        )
                        VALUES (
                            :saleId, 1, :s, :d, :p, :sku, :pname,
                            'UNIT', 100.000, :p, 100.000,
                            11900, 1190000, 0, 1190000,
                            1000000, 190000, 0, 5000.0000, 500000.00, false
                        )
                        """)
                .param("saleId", saleId).param("s", storeA).param("d", SALE_DAY)
                .param("p", prod1).param("sku", "SH85-P1-" + sfx).param("pname", "Yogurt SH85 " + sfx)
                .update();

        // Mermas en storeA:
        // APPROVED 1: prod1 EXPIRED 5 u * 1200 = $6.000
        insertShrinkage(storeA, prod1, "EXPIRED", "5.000", "1200.0000", "6000.00",
                "APPROVED", utcAt(LocalDate.of(2031, 5, 10), LocalTime.of(15, 0)));
        // APPROVED 2: prod2 BROKEN 2 u * 2000 = $4.000
        insertShrinkage(storeA, prod2, "BROKEN", "2.000", "2000.0000", "4000.00",
                "APPROVED", utcAt(LocalDate.of(2031, 5, 11), LocalTime.of(15, 0)));
        // Total APPROVED en storeA = $10.000 -> pctOfSales = 10000 / 1000000 * 100 = 1.00

        // PENDING_APPROVAL y REJECTED en storeA: NO deben contar
        insertShrinkage(storeA, prod1, "DAMAGED", "10.000", "5000.0000", "50000.00",
                "PENDING_APPROVAL", utcAt(LocalDate.of(2031, 5, 12), LocalTime.of(15, 0)));
        insertShrinkage(storeA, prod2, "THEFT_LOSS", "8.000", "5000.0000", "40000.00",
                "REJECTED", utcAt(LocalDate.of(2031, 5, 13), LocalTime.of(15, 0)));

        // APPROVED en storeB para probar multi-local y groupBy=store
        insertShrinkage(storeB, prod3, "EXPIRED", "2.000", "1000.0000", "2000.00",
                "APPROVED", utcAt(LocalDate.of(2031, 5, 14), LocalTime.of(15, 0)));
    }

    @Test
    void countsOnlyApprovedShrinkagesAndComputesOnePercentOfNetSales() throws Exception {
        // 1. Por tipo en storeA: $10.000 sobre ventas netas $1.000.000 => pctOfSales = 1.00
        MvcResult typeRes = mvc.perform(get("/api/v1/reports/shrinkage")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("groupBy", "type")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SHRINKAGE_READ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = JSON.readTree(typeRes.getResponse().getContentAsString());
        Map<String, JsonNode> byType = rowsByKey(root.get("rows"));
        assertThat(byType).containsOnlyKeys("EXPIRED", "BROKEN");
        assertThat(byType).doesNotContainKeys("DAMAGED", "THEFT_LOSS");

        assertThat(byType.get("EXPIRED").get("cost").decimalValue()).isEqualByComparingTo("6000.00");
        assertThat(byType.get("EXPIRED").get("pctOfSales").decimalValue()).isEqualByComparingTo("0.60");
        assertThat(byType.get("EXPIRED").get("count").asLong()).isEqualTo(1L);

        assertThat(byType.get("BROKEN").get("cost").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(byType.get("BROKEN").get("pctOfSales").decimalValue()).isEqualByComparingTo("0.40");
        assertThat(byType.get("BROKEN").get("count").asLong()).isEqualTo(1L);

        JsonNode totals = root.get("totals");
        assertThat(totals.get("qty").decimalValue()).isEqualByComparingTo("7.000");
        assertThat(totals.get("cost").decimalValue()).isEqualByComparingTo("10000.00");
        assertThat(totals.get("pctOfSales").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(totals.get("count").asLong()).isEqualTo(2L);

        // 2. Agrupación por month (en zona de negocio America/Santiago)
        MvcResult monthRes = mvc.perform(get("/api/v1/reports/shrinkage")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("groupBy", "month")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SHRINKAGE_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].key").value("2031-05"))
                .andReturn();
        JsonNode monthRow = JSON.readTree(monthRes.getResponse().getContentAsString()).get("rows").get(0);
        assertThat(monthRow.get("cost").decimalValue()).isEqualByComparingTo("10000.00");
        assertThat(monthRow.get("pctOfSales").decimalValue()).isEqualByComparingTo("1.00");

        // 3. Agrupación por category y store (multi-local)
        mvc.perform(get("/api/v1/reports/shrinkage")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("groupBy", "category")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SHRINKAGE_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2));

        MvcResult storeRes = mvc.perform(get("/api/v1/reports/shrinkage")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("groupBy", "store")
                        .param("storeIds", storeA + "," + storeB)
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SHRINKAGE_READ))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, JsonNode> byStore = rowsByKey(JSON.readTree(storeRes.getResponse().getContentAsString()).get("rows"));
        assertThat(byStore).containsOnlyKeys(String.valueOf(storeA), String.valueOf(storeB));
        assertThat(byStore.get(String.valueOf(storeB)).get("cost").decimalValue()).isEqualByComparingTo("2000.00");

        // 4. Top products por costo con limit=1
        MvcResult topRes = mvc.perform(get("/api/v1/reports/shrinkage/top-products")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("limit", "1")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SHRINKAGE_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].key").value(String.valueOf(prod1)))
                .andReturn();
        JsonNode topRow = JSON.readTree(topRes.getResponse().getContentAsString()).get("rows").get(0);
        assertThat(topRow.get("cost").decimalValue()).isEqualByComparingTo("6000.00");
        assertThat(topRow.get("pctOfSales").decimalValue()).isEqualByComparingTo("0.60");
    }

    @Test
    void rejectsUnauthorizedInvalidLimitInvalidGroupByAndUnassignedStore() throws Exception {
        mvc.perform(get("/api/v1/reports/shrinkage")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SALES_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(get("/api/v1/reports/shrinkage/top-products")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("limit", "101")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SHRINKAGE_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/reports/shrinkage")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("groupBy", "unknown")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SHRINKAGE_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/reports/shrinkage")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("storeIds", storeA + "," + storeB)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(restrictedUserId))
                                .authorities(new SimpleGrantedAuthority(Permissions.REPORT_SHRINKAGE_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));
    }

    private static Map<String, JsonNode> rowsByKey(JsonNode rows) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            map.put(row.get("key").asString(), row);
        }
        return map;
    }

    private long insertCategory(String name) {
        return jdbc.sql("INSERT INTO categories (name, active) VALUES (:n, true) RETURNING id")
                .param("n", name).query(Long.class).single();
    }

    private long insertProduct(String sku, String name, long categoryId, String avgCost) {
        return jdbc.sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, lot_controlled, active
                        )
                        VALUES (:sku, :name, :cat, 'UNIT', 'UNIT', 2000, :cost, false, false, true)
                        RETURNING id
                        """)
                .param("sku", sku).param("name", name).param("cat", categoryId)
                .param("cost", new BigDecimal(avgCost))
                .query(Long.class).single();
    }

    private void insertShrinkage(long storeId, long productId, String type, String qty,
                                 String unitCost, String totalCost, String status, OffsetDateTime occurredAt) {
        Long approvedBy = "APPROVED".equals(status) ? approverId : null;
        OffsetDateTime approvedAt = "APPROVED".equals(status) ? occurredAt : null;
        jdbc.sql("""
                        INSERT INTO shrinkages (
                            number, store_id, product_id, shrinkage_type, quantity, unit_cost, total_cost,
                            responsible_user_id, reported_by, status, approved_by, approved_at,
                            auto_approved, affects_stock, occurred_at
                        )
                        VALUES (
                            :num, :s, :p, :type, :qty, :uCost, :tCost,
                            :rep, :rep, :st, :appBy, :appAt,
                            false, true, :occ
                        )
                        """)
                .param("num", "SH85-" + sfx + "-" + (++seq))
                .param("s", storeId).param("p", productId).param("type", type)
                .param("qty", new BigDecimal(qty)).param("uCost", new BigDecimal(unitCost))
                .param("tCost", new BigDecimal(totalCost))
                .param("rep", reporterId).param("st", status)
                .param("appBy", approvedBy, Types.BIGINT).param("appAt", approvedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("occ", occurredAt)
                .update();
    }

    private OffsetDateTime utcAt(LocalDate day, LocalTime time) {
        return OffsetDateTime.ofInstant(
                clock.startOfDay(day).plusSeconds(time.toSecondOfDay()), ZoneOffset.UTC);
    }
}
