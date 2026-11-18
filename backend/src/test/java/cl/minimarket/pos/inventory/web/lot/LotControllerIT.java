package cl.minimarket.pos.inventory.web.lot;

import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de los endpoints de lotes, alertas de vencimiento y sugerencia FEFO
 * ({@code T52}, {@code HU-INV-08}, {@code HU-INV-09}).
 */
class LotControllerIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    BusinessClock clock;

    private String suffix;
    private long userId;
    private long storeA;
    private long storeB;
    private long categoryId;
    private long prodA;
    private long prodB;
    private LocalDate today;

    @BeforeEach
    void setUpData() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        today = clock.businessDate();
        userId = TestData.insertUser(ownerJdbc());
        storeA = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        storeB = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        assignStoreToUser(userId, storeA);

        categoryId = insertCategory("Lacteos-" + suffix);
        prodA = insertProduct("SKU-LA-" + suffix, "Yogurt Fresa " + suffix, categoryId, "UNIT", true);
        prodB = insertProduct("SKU-LB-" + suffix, "Queso Mantecoso " + suffix, categoryId, "KG", true);
    }

    @Test
    void listLots_appliesFiltersSortingStoreIsolationAndPermissions() throws Exception {
        long activeSoon = insertLot(storeA, prodA, "L-SOON-" + suffix, today.plusDays(5), new BigDecimal("6.000"), "ACTIVE");
        long activeFar = insertLot(storeA, prodA, "L-FAR-" + suffix, today.plusDays(40), new BigDecimal("4.000"), "ACTIVE");
        long depleted = insertLot(storeA, prodA, "L-ZERO-" + suffix, today.plusDays(3), BigDecimal.ZERO, "DEPLETED");
        long otherProd = insertLot(storeA, prodB, "L-QB-" + suffix, today.plusDays(10), new BigDecimal("2.500"), "ACTIVE");
        insertLot(storeB, prodA, "L-STB-" + suffix, today.plusDays(2), new BigDecimal("9.000"), "ACTIVE");

        // Sin permiso inventory:stock:read -> 403 ACCESS_DENIED
        mvc.perform(get("/api/v1/lots")
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_EXPIRY_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Local no asignado -> 403 STORE_ACCESS_DENIED
        mvc.perform(get("/api/v1/lots")
                        .header("X-Store-Id", storeB)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

        // Filtro productId + withStock=true -> solo ACTIVE de storeA en prodA (no DEPLETED, no prodB, no storeB)
        MvcResult activeRes = mvc.perform(get("/api/v1/lots")
                        .header("X-Store-Id", storeA)
                        .param("productId", String.valueOf(prodA))
                        .param("withStock", "true")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andReturn();
        List<Long> activeIds = extractLotIds(activeRes);
        assertThat(activeIds).containsExactly(activeSoon, activeFar);

        // Filtro productId + withStock=false -> solo lote DEPLETED
        MvcResult depletedRes = mvc.perform(get("/api/v1/lots")
                        .header("X-Store-Id", storeA)
                        .param("productId", String.valueOf(prodA))
                        .param("withStock", "false")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(extractLotIds(depletedRes)).containsExactly(depleted);

        // Filtro expiringWithinDays=15 + q=suffix -> activeSoon, depleted y otherProd (excluye activeFar y storeB)
        MvcResult expiringRes = mvc.perform(get("/api/v1/lots")
                        .header("X-Store-Id", storeA)
                        .param("expiringWithinDays", "15")
                        .param("q", suffix)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(extractLotIds(expiringRes)).containsExactly(depleted, activeSoon, otherProd);

        // Orden por nombre de producto descendente
        MvcResult sortedRes = mvc.perform(get("/api/v1/lots")
                        .header("X-Store-Id", storeA)
                        .param("q", suffix)
                        .param("withStock", "true")
                        .param("sort", "name,desc")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = jsonMapper.readTree(sortedRes.getResponse().getContentAsString()).get("content");
        assertThat(content.get(0).get("name").asString()).startsWith("Yogurt Fresa");
    }

    @Test
    void expiryAlerts_excludesZeroStockLots_respectsDaysWindowAndPermissions() throws Exception {
        long expiredLot = insertLot(storeA, prodA, "L-EXP-" + suffix, today.minusDays(2), new BigDecimal("2.000"), "ACTIVE");
        long soonLot = insertLot(storeA, prodA, "L-10D-" + suffix, today.plusDays(10), new BigDecimal("5.000"), "ACTIVE");
        insertLot(storeA, prodA, "L-ZERO-" + suffix, today.plusDays(1), BigDecimal.ZERO, "DEPLETED");
        insertLot(storeA, prodA, "L-LATE-" + suffix, today.plusDays(25), new BigDecimal("7.000"), "ACTIVE");
        insertLot(storeB, prodA, "L-OTHER-" + suffix, today.plusDays(3), new BigDecimal("4.000"), "ACTIVE");

        // Configura ventana del local A en 15 días
        upsertStoreSetting(storeA, "alerts.expiry.days_ahead", "15");

        // Sin permiso inventory:expiry:read -> 403 ACCESS_DENIED
        mvc.perform(get("/api/v1/expiry-alerts")
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Con ventana por defecto (15 días): aparecen expiredLot y soonLot; el lote con saldo 0 NO aparece
        MvcResult defaultRes = mvc.perform(get("/api/v1/expiry-alerts")
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_EXPIRY_READ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode alertsContent = jsonMapper.readTree(defaultRes.getResponse().getContentAsString()).get("content");
        List<Long> alertLotIds = StreamSupport.stream(alertsContent.spliterator(), false)
                .map(n -> n.get("lotId").asLong())
                .toList();
        assertThat(alertLotIds).containsExactly(expiredLot, soonLot);
        assertThat(StreamSupport.stream(alertsContent.spliterator(), false)
                .map(n -> n.get("lotNumber").asString())
                .toList()).doesNotContain("L-ZERO-" + suffix, "L-LATE-" + suffix, "L-OTHER-" + suffix);
        assertThat(StreamSupport.stream(alertsContent.spliterator(), false)
                .allMatch(n -> n.get("valuation").isNull())).isTrue();

        // Con ?days=30 incluye también L-LATE, pagina en SQL (totalElements=3) y sin catalog:cost:read mantiene valuation=null
        MvcResult page0NoCost = mvc.perform(get("/api/v1/expiry-alerts")
                        .header("X-Store-Id", storeA)
                        .param("days", "30")
                        .param("page", "0")
                        .param("size", "1")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_EXPIRY_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].lotId").value(expiredLot))
                .andExpect(jsonPath("$.content[0].daysToExpiry").value(-2))
                .andReturn();
        JsonNode page0NoCostItem = jsonMapper.readTree(page0NoCost.getResponse().getContentAsString())
                .get("content").get(0);
        assertThat(page0NoCostItem.get("valuation").isNull()).isTrue();

        // Con catalog:cost:read devuelve valuation != null (quantity * unit_cost) y paginación real
        MvcResult page0WithCost = mvc.perform(get("/api/v1/expiry-alerts")
                        .header("X-Store-Id", storeA)
                        .param("days", "30")
                        .param("page", "0")
                        .param("size", "2")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_EXPIRY_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();
        JsonNode page0Content = jsonMapper.readTree(page0WithCost.getResponse().getContentAsString()).get("content");
        assertThat(page0Content.get(0).get("lotId").asLong()).isEqualTo(expiredLot);
        assertThat(page0Content.get(0).get("valuation").decimalValue()).isEqualByComparingTo("1600.00");
        assertThat(page0Content.get(1).get("lotId").asLong()).isEqualTo(soonLot);
        assertThat(page0Content.get(1).get("valuation").decimalValue()).isEqualByComparingTo("4000.00");

        MvcResult page1WithCost = mvc.perform(get("/api/v1/expiry-alerts")
                        .header("X-Store-Id", storeA)
                        .param("days", "30")
                        .param("page", "1")
                        .param("size", "2")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_EXPIRY_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn();
        JsonNode page1Content = jsonMapper.readTree(page1WithCost.getResponse().getContentAsString()).get("content");
        assertThat(page1Content.get(0).get("lotNumber").asString()).isEqualTo("L-LATE-" + suffix);
        assertThat(page1Content.get(0).get("valuation").decimalValue()).isEqualByComparingTo("5600.00");
    }

    @Test
    void suggestFefo_returnsAllocationsInFefoOrderAndShortageWhenInsufficient() throws Exception {
        long lotDec1 = insertLot(storeA, prodA, "L-DEC01-" + suffix, today.plusDays(10), new BigDecimal("3.000"), "ACTIVE");
        long lotDec15 = insertLot(storeA, prodA, "L-DEC15-" + suffix, today.plusDays(25), new BigDecimal("4.000"), "ACTIVE");
        long lotSin = insertLot(storeA, prodA, "SIN-LOTE", null, new BigDecimal("2.000"), "ACTIVE");
        insertLot(storeA, prodA, "L-DEP-" + suffix, today.plusDays(1), BigDecimal.ZERO, "DEPLETED");

        // Cantidad cubierta (6 <= 9): 3 de lotDec1 + 3 de lotDec15, shortage = 0
        mvc.perform(get("/api/v1/lots/fefo")
                        .header("X-Store-Id", storeA)
                        .param("productId", String.valueOf(prodA))
                        .param("quantity", "6")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(prodA))
                .andExpect(jsonPath("$.requestedQty").value(6.0))
                .andExpect(jsonPath("$.shortage").value(0.0))
                .andExpect(jsonPath("$.allocations.length()").value(2))
                .andExpect(jsonPath("$.allocations[0].lotId").value(lotDec1))
                .andExpect(jsonPath("$.allocations[0].quantity").value(3.0))
                .andExpect(jsonPath("$.allocations[1].lotId").value(lotDec15))
                .andExpect(jsonPath("$.allocations[1].quantity").value(3.0));

        // Cantidad superior al saldo (12 > 9): asigna 3 + 4 + 2 (NULLS LAST) y shortage = 3
        mvc.perform(get("/api/v1/lots/fefo")
                        .header("X-Store-Id", storeA)
                        .param("productId", String.valueOf(prodA))
                        .param("quantity", "12")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(prodA))
                .andExpect(jsonPath("$.requestedQty").value(12.0))
                .andExpect(jsonPath("$.shortage").value(3.0))
                .andExpect(jsonPath("$.allocations.length()").value(3))
                .andExpect(jsonPath("$.allocations[0].lotId").value(lotDec1))
                .andExpect(jsonPath("$.allocations[1].lotId").value(lotDec15))
                .andExpect(jsonPath("$.allocations[2].lotId").value(lotSin))
                .andExpect(jsonPath("$.allocations[2].quantity").value(2.0));

        // Producto sin stock en ningún lote: allocations vacío y shortage = cantidad solicitada
        mvc.perform(get("/api/v1/lots/fefo")
                        .header("X-Store-Id", storeA)
                        .param("productId", String.valueOf(prodB))
                        .param("quantity", "2.5")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocations.length()").value(0))
                .andExpect(jsonPath("$.shortage").value(2.5));
    }

    @Test
    void updateLot_updatesAndAuditsLotUpdated_andHandles403_404_409() throws Exception {
        LocalDate initialExp = today.plusDays(20);
        LocalDate updatedExp = today.plusDays(35);
        long lotId = insertLot(storeA, prodA, "L-ORIG-" + suffix, initialExp, new BigDecimal("10.000"), "ACTIVE");
        long otherLotSameProduct = insertLot(storeA, prodA, "L-DUP-" + suffix, updatedExp, new BigDecimal("5.000"), "ACTIVE");
        long otherStoreLotId = insertLot(storeB, prodA, "L-STB-" + suffix, initialExp, new BigDecimal("8.000"), "ACTIVE");
        assertThat(otherLotSameProduct).isPositive();

        String validBody = jsonMapper.writeValueAsString(Map.of(
                "lotNumber", "L-CORR-" + suffix,
                "expiryDate", updatedExp.toString(),
                "reason", "Error de digitación en etiqueta del proveedor",
                "version", 0));

        // 403 ACCESS_DENIED sin permiso inventory:lot:update
        mvc.perform(put("/api/v1/lots/{id}", lotId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 404 NOT_FOUND cuando el lote pertenece a otro local (store isolation)
        mvc.perform(put("/api/v1/lots/{id}", otherStoreLotId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_LOT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 404 NOT_FOUND cuando el id no existe
        mvc.perform(put("/api/v1/lots/{id}", 999_999_999L)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_LOT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 409 DUPLICATED_VALUE si colisiona con otro lote (store, product, lotNumber, expiryDate)
        String dupBody = jsonMapper.writeValueAsString(Map.of(
                "lotNumber", "L-DUP-" + suffix,
                "expiryDate", updatedExp.toString(),
                "reason", "Intento duplicar lote existente",
                "version", 0));
        mvc.perform(put("/api/v1/lots/{id}", lotId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_LOT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dupBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // 409 CONCURRENT_MODIFICATION si version no coincide
        String staleBody = jsonMapper.writeValueAsString(Map.of(
                "lotNumber", "L-CORR-" + suffix,
                "expiryDate", updatedExp.toString(),
                "reason", "Versión obsoleta",
                "version", 99));
        mvc.perform(put("/api/v1/lots/{id}", lotId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_LOT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        // Actualización exitosa -> 200 OK, incrementa version y registra auditoría LOT_UPDATED
        mvc.perform(put("/api/v1/lots/{id}", lotId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_LOT_UPDATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lotId))
                .andExpect(jsonPath("$.lotNumber").value("L-CORR-" + suffix))
                .andExpect(jsonPath("$.expiryDate").value(updatedExp.toString()))
                .andExpect(jsonPath("$.version").value(1));

        record AuditRow(String action, String entityType, String reason, String oldJson, String newJson) {
        }
        AuditRow auditRow = ownerJdbc().sql("""
                SELECT action, entity_type, reason, old_value::text AS old_json, new_value::text AS new_json
                FROM audit_log
                WHERE action = 'LOT_UPDATED'
                  AND entity_type = 'lot'
                  AND entity_id = :entityId
                ORDER BY id DESC
                LIMIT 1
                """)
                .param("entityId", String.valueOf(lotId))
                .query((rs, rowNum) -> new AuditRow(
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("reason"),
                        rs.getString("old_json"),
                        rs.getString("new_json")))
                .single();

        assertThat(auditRow.action()).isEqualTo("LOT_UPDATED");
        assertThat(auditRow.entityType()).isEqualTo("lot");
        assertThat(auditRow.reason()).isNotNull().isEqualTo("Error de digitación en etiqueta del proveedor");
        JsonNode oldNode = jsonMapper.readTree(auditRow.oldJson());
        JsonNode newNode = jsonMapper.readTree(auditRow.newJson());
        assertThat(oldNode.get("lotNumber").asString()).isEqualTo("L-ORIG-" + suffix);
        assertThat(oldNode.get("expiryDate").asString()).isEqualTo(initialExp.toString());
        assertThat(newNode.get("lotNumber").asString()).isEqualTo("L-CORR-" + suffix);
        assertThat(newNode.get("expiryDate").asString()).isEqualTo(updatedExp.toString());
    }

    private List<Long> extractLotIds(MvcResult result) throws Exception {
        JsonNode content = jsonMapper.readTree(result.getResponse().getContentAsString()).get("content");
        return StreamSupport.stream(content.spliterator(), false)
                .map(n -> n.get("id").asLong())
                .toList();
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

    private long insertProduct(String sku, String name, long catId, String uom, boolean lotControlled) {
        return ownerJdbc().sql("""
                INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                      price_gross, avg_cost, vat_exempt, restricted_type,
                                      lot_controlled, active, created_at, updated_at, version)
                VALUES (:sku, :name, :categoryId, :saleType, :uom,
                        1500, 800.0000, false, NULL,
                        :lotControlled, true, now(), now(), 0)
                RETURNING id
                """)
                .param("sku", sku)
                .param("name", name)
                .param("categoryId", catId)
                .param("saleType", "KG".equals(uom) ? "WEIGHT" : "UNIT")
                .param("uom", uom)
                .param("lotControlled", lotControlled)
                .query(Long.class)
                .single();
    }

    private long insertLot(
            long storeId,
            long productId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal quantity,
            String status) {
        return ownerJdbc().sql("""
                INSERT INTO lots (store_id, product_id, lot_number, expiry_date,
                                  quantity, unit_cost, received_at, status, version)
                VALUES (:storeId, :productId, :lotNumber, :expiryDate,
                        :qty, 800.0000, now(), :status, 0)
                RETURNING id
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .param("lotNumber", lotNumber)
                .param("expiryDate", expiryDate, Types.DATE)
                .param("qty", quantity)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private void upsertStoreSetting(long storeId, String key, String jsonValue) {
        ownerJdbc().sql("""
                DELETE FROM settings
                WHERE key = :key AND scope = 'STORE' AND store_id = :storeId
                """)
                .param("key", key)
                .param("storeId", storeId)
                .update();
        ownerJdbc().sql("""
                INSERT INTO settings (key, scope, store_id, value, value_type, description)
                VALUES (:key, 'STORE', :storeId, CAST(:val AS jsonb), 'NUMBER', 'Test expiry days ahead')
                """)
                .param("key", key)
                .param("storeId", storeId)
                .param("val", jsonValue)
                .update();
    }
}
