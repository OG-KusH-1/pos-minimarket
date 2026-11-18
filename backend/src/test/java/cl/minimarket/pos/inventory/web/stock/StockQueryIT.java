package cl.minimarket.pos.inventory.web.stock;

import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de los endpoints de consulta de stock, alertas, límites y kardex
 * (T24, HU-INV-01, HU-INV-02, HU-INV-04).
 */
class StockQueryIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    StockApi stockApi;

    @Autowired
    TransactionTemplate tx;

    private String suffix;
    private long userId;
    private long storeA;
    private long storeB;
    private long categoryId;
    private long prodZero;
    private long prodBelowMin;
    private long prodAboveMax;
    private long prodOk;

    @BeforeEach
    void setUpData() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = TestData.insertUser(ownerJdbc());
        storeA = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        storeB = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        assignStoreToUser(userId, storeA);

        categoryId = insertCategory("Bebidas-" + suffix);
        prodZero = insertProduct("SKU-Z-" + suffix, "Agua Cero " + suffix, categoryId, new BigDecimal("500.0000"), false);
        prodBelowMin = insertProduct("SKU-B-" + suffix, "Jugo Bajo " + suffix, categoryId, new BigDecimal("1000.0000"), false);
        prodAboveMax = insertProduct("SKU-A-" + suffix, "Leche Sobre " + suffix, categoryId, new BigDecimal("800.0000"), true);
        prodOk = insertProduct("SKU-O-" + suffix, "Te Normal " + suffix, categoryId, new BigDecimal("600.0000"), false);

        insertStock(storeA, prodZero, new BigDecimal("0.000"), new BigDecimal("5.000"), new BigDecimal("20.000"));
        insertStock(storeA, prodBelowMin, new BigDecimal("8.000"), new BigDecimal("10.000"), new BigDecimal("30.000"));
        insertStock(storeA, prodAboveMax, new BigDecimal("25.000"), new BigDecimal("5.000"), new BigDecimal("20.000"));
        insertStock(storeA, prodOk, new BigDecimal("12.000"), new BigDecimal("5.000"), new BigDecimal("20.000"));

        insertStock(storeB, prodBelowMin, new BigDecimal("15.000"), new BigDecimal("5.000"), new BigDecimal("30.000"));
    }

    @Test
    void listStock_filtersByBelowMinZeroAboveMax_andEnforcesStoreAccess() throws Exception {
        // Local no asignado en X-Store-Id → 403 STORE_ACCESS_DENIED (HU-INV-01)
        mvc.perform(get("/api/v1/stock")
                        .header("X-Store-Id", storeB)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

        // Filtro BELOW_MIN con q de la categoría de prueba
        MvcResult belowMinRes = mvc.perform(get("/api/v1/stock")
                        .header("X-Store-Id", storeA)
                        .param("categoryId", String.valueOf(categoryId))
                        .param("status", "BELOW_MIN")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode belowMinContent = jsonMapper.readTree(belowMinRes.getResponse().getContentAsString()).get("content");
        List<Long> belowMinIds = StreamSupport.stream(belowMinContent.spliterator(), false)
                .map(node -> node.get("productId").asLong())
                .toList();
        assertThat(belowMinIds).containsExactlyInAnyOrder(prodZero, prodBelowMin);
        // Sin catalog:cost:read → valuation es null
        assertThat(belowMinContent.get(0).get("valuation").isNull()).isTrue();

        // Filtro ZERO
        MvcResult zeroRes = mvc.perform(get("/api/v1/stock")
                        .header("X-Store-Id", storeA)
                        .param("categoryId", String.valueOf(categoryId))
                        .param("status", "ZERO")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode zeroContent = jsonMapper.readTree(zeroRes.getResponse().getContentAsString()).get("content");
        assertThat(zeroContent).hasSize(1);
        assertThat(zeroContent.get(0).get("productId").asLong()).isEqualTo(prodZero);
        assertThat(zeroContent.get(0).get("status").asText()).isEqualTo("ZERO");

        // Filtro ABOVE_MAX + lotControlled + catalog:cost:read → incluye valuation
        mvc.perform(get("/api/v1/stock")
                        .header("X-Store-Id", storeA)
                        .param("categoryId", String.valueOf(categoryId))
                        .param("status", "ABOVE_MAX")
                        .param("lotControlled", "true")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].productId").value(prodAboveMax))
                .andExpect(jsonPath("$.content[0].status").value("ABOVE_MAX"))
                .andExpect(jsonPath("$.content[0].valuation").value(20000.00));
    }

    @Test
    void getByStore_respectsAssignedStoresAndAllAccess() throws Exception {
        // Sin all-access: ve solo storeA, pero companyQuantity suma storeA (8) + storeB (15) = 23
        mvc.perform(get("/api/v1/stock/{productId}", prodBelowMin)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(prodBelowMin))
                .andExpect(jsonPath("$.stores.length()").value(1))
                .andExpect(jsonPath("$.stores[0].storeId").value(storeA))
                .andExpect(jsonPath("$.companyQuantity").value(23.0));

        // Con all-access: ve storeA y storeB
        mvc.perform(get("/api/v1/stock/{productId}", prodBelowMin)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ),
                                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores.length()").value(2));
    }

    @Test
    void updateLimits_andAlerts_validateMinMaxAndExposeExpiringLots() throws Exception {
        // maxQty < minQty → 400 VALIDATION_ERROR (HU-INV-04)
        mvc.perform(put("/api/v1/stock/{productId}/limits", prodOk)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_LIMITS_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "minQty": 15,
                                  "maxQty": 10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Actualiza límites válidos (min=15, stock=12 → pasa a BELOW_MIN)
        mvc.perform(put("/api/v1/stock/{productId}/limits", prodOk)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_LIMITS_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "minQty": 15,
                                  "maxQty": 40
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minQty").value(15.0))
                .andExpect(jsonPath("$.maxQty").value(40.0))
                .andExpect(jsonPath("$.status").value("BELOW_MIN"));

        // Inserta lote próximo a vencer para validar alerts
        ownerJdbc().sql("""
                INSERT INTO lots (store_id, product_id, lot_number, expiry_date, quantity, unit_cost, received_at, status)
                VALUES (:storeId, :productId, :lotNumber, :expiryDate, 5.000, 800.0000, now(), 'ACTIVE')
                """)
                .param("storeId", storeA)
                .param("productId", prodAboveMax)
                .param("lotNumber", "LOT-" + suffix)
                .param("expiryDate", LocalDate.now().plusDays(3))
                .update();

        MvcResult alertsRes = mvc.perform(get("/api/v1/stock/alerts")
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zeroCount").value(1))
                .andExpect(jsonPath("$.expiringCount").value(1))
                .andExpect(jsonPath("$.expiring[0].lotNumber").value("LOT-" + suffix))
                .andReturn();

        JsonNode belowMin = jsonMapper.readTree(alertsRes.getResponse().getContentAsString()).get("belowMin");
        List<Long> alertProductIds = StreamSupport.stream(belowMin.spliterator(), false)
                .map(node -> node.get("productId").asLong())
                .toList();
        assertThat(alertProductIds).contains(prodZero, prodBelowMin, prodOk);
    }

    @Test
    void kardex_showsMovementsWithUserAndHidesCostWithoutPermission_andIsReadOnly() throws Exception {
        // Sin productId → 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/kardex")
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_KARDEX_READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Registra una salida SALE de 3 unidades (de 12 pasa a 9)
        tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                storeA,
                StockMovementType.SALE,
                StockSourceType.SALE,
                9901L,
                userId,
                "Venta POS test",
                List.of(new StockLine(prodOk, new BigDecimal("3.000"), null, null, null, null, null)))));

        // Sin catalog:cost:read → unitCost es null o ausente
        MvcResult kardexNoCost = mvc.perform(get("/api/v1/kardex")
                        .header("X-Store-Id", storeA)
                        .param("productId", String.valueOf(prodOk))
                        .param("movementType", "SALE")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_KARDEX_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].movementType").value("SALE"))
                .andExpect(jsonPath("$.content[0].quantityDelta").value(-3.0))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(9.0))
                .andExpect(jsonPath("$.content[0].reason").value("Venta POS test"))
                .andExpect(jsonPath("$.content[0].username").isNotEmpty())
                .andReturn();
        JsonNode movementNode = jsonMapper.readTree(kardexNoCost.getResponse().getContentAsString())
                .get("content").get(0);
        assertThat(movementNode.path("unitCost").isMissingNode() || movementNode.path("unitCost").isNull()).isTrue();

        // Con catalog:cost:read → unitCost visible
        mvc.perform(get("/api/v1/kardex")
                        .header("X-Store-Id", storeA)
                        .param("productId", String.valueOf(prodOk))
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_KARDEX_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].unitCost").value(600.0));

        // No hay endpoint de escritura en /kardex (HU-INV-02)
        mvc.perform(post("/api/v1/kardex")
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_KARDEX_READ))))
                .andExpect(status().isMethodNotAllowed());
    }

    private void assignStoreToUser(long uid, long sid) {
        ownerJdbc().sql("""
                INSERT INTO user_stores (user_id, store_id, is_default)
                VALUES (:userId, :storeId, true)
                ON CONFLICT (user_id, store_id) DO NOTHING
                """)
                .param("userId", uid)
                .param("storeId", sid)
                .update();
    }

    private long insertCategory(String name) {
        return ownerJdbc().sql("""
                INSERT INTO categories (name, active, created_at, updated_at, version)
                VALUES (:name, true, now(), now(), 0)
                RETURNING id
                """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertProduct(String sku, String name, long catId, BigDecimal avgCost, boolean lotControlled) {
        return ownerJdbc().sql("""
                INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                      price_gross, avg_cost, vat_exempt, restricted_type,
                                      lot_controlled, active, created_at, updated_at, version)
                VALUES (:sku, :name, :categoryId, 'UNIT', 'UNIT',
                        1500, :avgCost, false, NULL,
                        :lotControlled, true, now(), now(), 0)
                RETURNING id
                """)
                .param("sku", sku)
                .param("name", name)
                .param("categoryId", catId)
                .param("avgCost", avgCost)
                .param("lotControlled", lotControlled)
                .query(Long.class)
                .single();
    }

    private void insertStock(long sid, long pid, BigDecimal qty, BigDecimal minQty, BigDecimal maxQty) {
        ownerJdbc().sql("""
                INSERT INTO stock (store_id, product_id, quantity, min_qty, max_qty)
                VALUES (:storeId, :productId, :qty, :minQty, :maxQty)
                """)
                .param("storeId", sid)
                .param("productId", pid)
                .param("qty", qty)
                .param("minQty", minQty)
                .param("maxQty", maxQty)
                .update();
    }
}
