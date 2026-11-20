package cl.minimarket.pos.promotions.web;

import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de promociones: CRUD, auditoría {@code PROMOTION_CHANGED}, validaciones,
 * bloqueo optimista, permisos y {@code GET /api/v1/pos/promotions/active} ({@code PLAN.md} H.3 T68).
 */
class PromotionIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BusinessClock businessClock;

    private String suffix;
    private long storeId;
    private long otherStoreId;
    private long userId;
    private long categoryId;
    private long brandId;
    private long productId1;
    private long productId2;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        otherStoreId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        userId = TestData.insertUser(jdbc);
        jdbc.sql("INSERT INTO user_stores (user_id, store_id) VALUES (:u, :s) ON CONFLICT DO NOTHING")
                .param("u", userId)
                .param("s", storeId)
                .update();
        jdbc.sql("INSERT INTO user_stores (user_id, store_id) VALUES (:u, :s) ON CONFLICT DO NOTHING")
                .param("u", userId)
                .param("s", otherStoreId)
                .update();

        categoryId = jdbc.sql("INSERT INTO categories (name, active) VALUES (:n, true) RETURNING id")
                .param("n", "CatPromo-" + suffix)
                .query(Long.class)
                .single();
        brandId = jdbc.sql("INSERT INTO brands (name, active) VALUES (:n, true) RETURNING id")
                .param("n", "BrandPromo-" + suffix)
                .query(Long.class)
                .single();
        productId1 = createProduct("PR1-" + suffix, "Producto Promo 1 " + suffix, 1500);
        productId2 = createProduct("PR2-" + suffix, "Producto Promo 2 " + suffix, 2000);
    }

    @Test
    void crudLifecycleAuditsPromotionChangedAndEnforcesPermissionsAndOptimisticLock() throws Exception {
        Instant now = businessClock.now();
        Instant from = now.minusSeconds(3600);
        Instant to = now.plusSeconds(86400);

        // Sin permiso de gestión → 403
        mvc.perform(post("/api/v1/promotions")
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Promo Prohibida %s",
                                  "promotionType": "PERCENT",
                                  "validFrom": "%s",
                                  "percent": 15,
                                  "items": [{"targetType": "PRODUCT", "targetId": %d}]
                                }
                                """.formatted(suffix, from, productId1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        long auditBefore = countPromotionAudits();

        // Crear promoción PERCENT sobre categoría
        MvcResult createdResult = mvc.perform(post("/api/v1/promotions")
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Promo Cat %s",
                                  "description": "Descuento categoría",
                                  "promotionType": "PERCENT",
                                  "priority": 25,
                                  "stackable": false,
                                  "validFrom": "%s",
                                  "validTo": "%s",
                                  "allStores": false,
                                  "storeIds": [%d],
                                  "percent": 15,
                                  "maxApplicationsPerSale": 3,
                                  "items": [{"targetType": "CATEGORY", "targetId": %d, "requiredQty": 1}]
                                }
                                """.formatted(suffix, from, to, storeId, categoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Promo Cat " + suffix))
                .andExpect(jsonPath("$.promotionType").value("PERCENT"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.items[0].targetName").value("CatPromo-" + suffix))
                .andExpect(jsonPath("$.storeIds[0]").value(storeId))
                .andReturn();

        JsonNode created = jsonMapper.readTree(createdResult.getResponse().getContentAsString());
        long promoId = created.get("id").asLong();
        long version0 = created.get("version").asLong();

        // GET /{id}
        mvc.perform(get("/api/v1/promotions/{id}", promoId)
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(promoId))
                .andExpect(jsonPath("$.items[0].targetType").value("CATEGORY"));

        // GET /promotions con filtros productId (matchea por categoría), type, active y sort
        mvc.perform(get("/api/v1/promotions")
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_READ)))
                        .param("active", "true")
                        .param("type", "PERCENT")
                        .param("productId", String.valueOf(productId1))
                        .param("vigentAt", now.toString())
                        .param("sort", "priority,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + promoId + ")]").exists());

        // PUT /{id} cambiando a COMBO (2 productos)
        MvcResult updatedResult = mvc.perform(put("/api/v1/promotions/{id}", promoId)
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Combo Editado %s",
                                  "promotionType": "COMBO",
                                  "priority": 10,
                                  "stackable": false,
                                  "validFrom": "%s",
                                  "validTo": "%s",
                                  "allStores": true,
                                  "comboPrice": 2800,
                                  "items": [
                                    {"targetType": "PRODUCT", "targetId": %d, "requiredQty": 1},
                                    {"targetType": "PRODUCT", "targetId": %d, "requiredQty": 1}
                                  ],
                                  "version": %d
                                }
                                """.formatted(suffix, from, to, productId1, productId2, version0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Combo Editado " + suffix))
                .andExpect(jsonPath("$.promotionType").value("COMBO"))
                .andExpect(jsonPath("$.allStores").value(true))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();

        long version1 = jsonMapper.readTree(updatedResult.getResponse().getContentAsString())
                .get("version").asLong();
        assertThat(version1).isGreaterThan(version0);

        // PUT con versión obsoleta → 409 CONCURRENT_MODIFICATION
        mvc.perform(put("/api/v1/promotions/{id}", promoId)
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Combo Stale %s",
                                  "promotionType": "COMBO",
                                  "validFrom": "%s",
                                  "comboPrice": 2800,
                                  "items": [
                                    {"targetType": "PRODUCT", "targetId": %d},
                                    {"targetType": "PRODUCT", "targetId": %d}
                                  ],
                                  "version": %d
                                }
                                """.formatted(suffix, from, productId1, productId2, version0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        // Desactivar y reactivar
        mvc.perform(post("/api/v1/promotions/{id}/deactivate", promoId)
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE))))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/promotions/{id}/activate", promoId)
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE))))
                .andExpect(status().isNoContent());

        // Verificar 4 registros de auditoría PROMOTION_CHANGED (create, update, deactivate, activate)
        assertThat(countPromotionAudits() - auditBefore).isEqualTo(4);
        long forPromo = jdbc.sql("""
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'PROMOTION_CHANGED' AND entity_type = 'promotion' AND entity_id = :eid
                """)
                .param("eid", String.valueOf(promoId))
                .query(Long.class)
                .single();
        assertThat(forPromo).isEqualTo(4);
    }

    @Test
    void validatesTypeChecksComboRulesAndStoreIds() throws Exception {
        Instant now = businessClock.now();

        // COMBO con menos de 2 productos → 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/promotions")
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Combo Inválido %s",
                                  "promotionType": "COMBO",
                                  "validFrom": "%s",
                                  "comboPrice": 1000,
                                  "items": [{"targetType": "PRODUCT", "targetId": %d}]
                                }
                                """.formatted(suffix, now, productId1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // BUY_X_PAY_Y con buyQty <= payQty → 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/promotions")
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "NxM Inválido %s",
                                  "promotionType": "BUY_X_PAY_Y",
                                  "validFrom": "%s",
                                  "buyQty": 2,
                                  "payQty": 2,
                                  "items": [{"targetType": "BRAND", "targetId": %d}]
                                }
                                """.formatted(suffix, now, brandId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void posActiveReturnsOnlyVigentPromotionsForCurrentStoreAndTime() throws Exception {
        Instant now = businessClock.now();
        ZonedDateTime zdt = now.atZone(businessClock.zone());
        int currentDow = zdt.getDayOfWeek().getValue();
        int otherDow = (currentDow % 7) + 1;
        Instant from = now.minusSeconds(3600);
        Instant to = now.plusSeconds(3600);

        // 1. Vigente en storeId (día actual, allStores=false)
        long vigentId = createPromotionViaApi("""
                {
                  "name": "Vigente Local %s",
                  "promotionType": "BUY_X_PAY_Y",
                  "priority": 5,
                  "validFrom": "%s",
                  "validTo": "%s",
                  "daysOfWeek": [%d],
                  "allStores": false,
                  "storeIds": [%d],
                  "buyQty": 3,
                  "payQty": 2,
                  "items": [{"targetType": "PRODUCT", "targetId": %d}]
                }
                """.formatted(suffix, from, to, currentDow, storeId, productId1));

        // 2. Vigente solo en otherStoreId (no debe salir en storeId)
        long otherStorePromoId = createPromotionViaApi("""
                {
                  "name": "Otro Local %s",
                  "promotionType": "PERCENT",
                  "validFrom": "%s",
                  "validTo": "%s",
                  "allStores": false,
                  "storeIds": [%d],
                  "percent": 20,
                  "items": [{"targetType": "PRODUCT", "targetId": %d}]
                }
                """.formatted(suffix, from, to, otherStoreId, productId1));

        // 3. Otro día de la semana (no debe salir hoy)
        long otherDayPromoId = createPromotionViaApi("""
                {
                  "name": "Otro Día %s",
                  "promotionType": "FIXED_AMOUNT",
                  "validFrom": "%s",
                  "validTo": "%s",
                  "daysOfWeek": [%d],
                  "allStores": true,
                  "amount": 500,
                  "items": [{"targetType": "PRODUCT", "targetId": %d}]
                }
                """.formatted(suffix, from, to, otherDow, productId1));

        // 4. Vencida (no debe salir)
        long expiredPromoId = createPromotionViaApi("""
                {
                  "name": "Vencida %s",
                  "promotionType": "PERCENT",
                  "validFrom": "%s",
                  "validTo": "%s",
                  "allStores": true,
                  "percent": 10,
                  "items": [{"targetType": "PRODUCT", "targetId": %d}]
                }
                """.formatted(suffix, from.minusSeconds(7200), now.minusSeconds(60), productId1));

        // 5. Desactivada (no debe salir)
        long inactivePromoId = createPromotionViaApi("""
                {
                  "name": "Inactiva %s",
                  "promotionType": "PERCENT",
                  "validFrom": "%s",
                  "validTo": "%s",
                  "allStores": true,
                  "percent": 30,
                  "items": [{"targetType": "PRODUCT", "targetId": %d}]
                }
                """.formatted(suffix, from, to, productId1));
        mvc.perform(post("/api/v1/promotions/{id}/deactivate", inactivePromoId)
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE))))
                .andExpect(status().isNoContent());

        MvcResult posResult = mvc.perform(get("/api/v1/pos/promotions/active")
                        .header("X-Store-Id", storeId)
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode activeList = jsonMapper.readTree(posResult.getResponse().getContentAsString());
        assertThat(activeList).anyMatch(n -> n.get("id").asLong() == vigentId
                && "3x2".equals(n.get("label").asText()));
        assertThat(activeList).noneMatch(n -> n.get("id").asLong() == otherStorePromoId);
        assertThat(activeList).noneMatch(n -> n.get("id").asLong() == otherDayPromoId);
        assertThat(activeList).noneMatch(n -> n.get("id").asLong() == expiredPromoId);
        assertThat(activeList).noneMatch(n -> n.get("id").asLong() == inactivePromoId);
    }

    private long createPromotionViaApi(String json) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/promotions")
                        .with(user(String.valueOf(userId))
                                .authorities(new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createProduct(String sku, String name, long priceGross) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/products")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s",
                                  "name": "%s",
                                  "categoryId": %d,
                                  "brandId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "vatExempt": false,
                                  "initialPriceGross": %d,
                                  "barcodes": []
                                }
                                """.formatted(sku, name, categoryId, brandId, priceGross)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long countPromotionAudits() {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'PROMOTION_CHANGED'")
                .query(Long.class)
                .single();
    }
}
