package cl.minimarket.pos.reporting.inventory;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del reporte de valorización de inventario ({@code T85}, {@code HU-REP-07}).
 */
class InventoryValuationReportIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private long storeA;
    private long storeB;
    private long restrictedUserId;
    private long categoryA;
    private long categoryB;
    private long product1;
    private long product2;
    private long productZero;

    @BeforeEach
    void setUp() {
        String sfx = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeA = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeB = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        restrictedUserId = TestData.insertUser(jdbc);
        jdbc.sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:u, :s, true)")
                .param("u", restrictedUserId)
                .param("s", storeA)
                .update();

        categoryA = insertCategory("Abarrotes IV85 " + sfx);
        categoryB = insertCategory("Lácteos IV85 " + sfx);

        product1 = insertProduct("IV85-P1-" + sfx, "Arroz IV85 " + sfx, categoryA, "500.0000");
        product2 = insertProduct("IV85-P2-" + sfx, "Queso IV85 " + sfx, categoryB, "1250.5000");
        productZero = insertProduct("IV85-P0-" + sfx, "Sin Stock IV85 " + sfx, categoryA, "999.0000");

        // storeA: P1=10 * 500 = 5000.00; P2=2 * 1250.50 = 2501.00; P0=0 (excluido)
        insertStock(storeA, product1, "10.000");
        insertStock(storeA, product2, "2.000");
        insertStock(storeA, productZero, "0.000");

        // storeB: P1=4 * 500 = 2000.00
        insertStock(storeB, product1, "4.000");
    }

    @Test
    void computesCurrentValuationWithoutDateRangeAndSupportsAllGroupings() throws Exception {
        // 1. Sin fechas, groupBy por defecto = category en storeA
        MvcResult catRes = mvc.perform(get("/api/v1/reports/inventory-valuation")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_INVENTORY_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.from").doesNotExist())
                .andExpect(jsonPath("$.filters.to").doesNotExist())
                .andReturn();

        JsonNode catRoot = JSON.readTree(catRes.getResponse().getContentAsString());
        Map<String, JsonNode> byCat = rowsByKey(catRoot.get("rows"));
        assertThat(byCat).containsOnlyKeys(String.valueOf(categoryA), String.valueOf(categoryB));
        assertThat(byCat.get(String.valueOf(categoryA)).get("qty").decimalValue()).isEqualByComparingTo("10.000");
        assertThat(byCat.get(String.valueOf(categoryA)).get("valuation").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(byCat.get(String.valueOf(categoryB)).get("valuation").decimalValue()).isEqualByComparingTo("2501.00");

        BigDecimal sumPct = byCat.get(String.valueOf(categoryA)).get("pct").decimalValue()
                .add(byCat.get(String.valueOf(categoryB)).get("pct").decimalValue());
        assertThat(sumPct).isEqualByComparingTo("100.00");

        JsonNode catTotals = catRoot.get("totals");
        assertThat(catTotals.get("qty").decimalValue()).isEqualByComparingTo("12.000");
        assertThat(catTotals.get("valuation").decimalValue()).isEqualByComparingTo("7501.00");
        assertThat(catTotals.get("pct").decimalValue()).isEqualByComparingTo("100.00");

        // 2. groupBy=product en storeA: excluye productZero
        MvcResult prodRes = mvc.perform(get("/api/v1/reports/inventory-valuation")
                        .param("groupBy", "product")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_INVENTORY_READ))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, JsonNode> byProd = rowsByKey(JSON.readTree(prodRes.getResponse().getContentAsString()).get("rows"));
        assertThat(byProd).containsOnlyKeys(String.valueOf(product1), String.valueOf(product2));
        assertThat(byProd).doesNotContainKey(String.valueOf(productZero));

        // 3. groupBy=store con multi-local storeA,storeB
        MvcResult storeRes = mvc.perform(get("/api/v1/reports/inventory-valuation")
                        .param("groupBy", "store")
                        .param("storeIds", storeA + "," + storeB)
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_INVENTORY_READ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode storeRoot = JSON.readTree(storeRes.getResponse().getContentAsString());
        Map<String, JsonNode> byStore = rowsByKey(storeRoot.get("rows"));
        assertThat(byStore).containsOnlyKeys(String.valueOf(storeA), String.valueOf(storeB));
        assertThat(byStore.get(String.valueOf(storeA)).get("valuation").decimalValue()).isEqualByComparingTo("7501.00");
        assertThat(byStore.get(String.valueOf(storeB)).get("valuation").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(storeRoot.get("totals").get("qty").decimalValue()).isEqualByComparingTo("16.000");
        assertThat(storeRoot.get("totals").get("valuation").decimalValue()).isEqualByComparingTo("9501.00");
    }

    @Test
    void rejectsUnauthorizedPartialRangeAndUnassignedStore() throws Exception {
        mvc.perform(get("/api/v1/reports/inventory-valuation")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SALES_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(get("/api/v1/reports/inventory-valuation")
                        .param("from", "2031-01-01")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_INVENTORY_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/reports/inventory-valuation")
                        .param("groupBy", "bad")
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_INVENTORY_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/reports/inventory-valuation")
                        .param("storeIds", storeA + "," + storeB)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(restrictedUserId))
                                .authorities(new SimpleGrantedAuthority(Permissions.REPORT_INVENTORY_READ))))
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
                        VALUES (:sku, :name, :cat, 'UNIT', 'UNIT', 1000, :cost, false, false, true)
                        RETURNING id
                        """)
                .param("sku", sku).param("name", name).param("cat", categoryId)
                .param("cost", new BigDecimal(avgCost))
                .query(Long.class).single();
    }

    private void insertStock(long storeId, long productId, String qty) {
        jdbc.sql("INSERT INTO stock (store_id, product_id, quantity) VALUES (:s, :p, :q)")
                .param("s", storeId).param("p", productId).param("q", new BigDecimal(qty))
                .update();
    }
}
