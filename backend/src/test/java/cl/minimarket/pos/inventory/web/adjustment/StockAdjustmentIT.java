package cl.minimarket.pos.inventory.web.adjustment;

import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del módulo de ajustes de stock con aprobación
 * ({@code T49}, {@code HU-INV-05}):
 * <ul>
 *   <li>Creación en {@code PENDING_APPROVAL} sin mover {@code stock} ni generar {@code stock_movements}.</li>
 *   <li>Idempotencia ({@code @Idempotent}), visibilidad de costos ({@code ROLES_PERMISOS.md} D.2), filtros y permisos 403.</li>
 *   <li>Segregación de funciones (RN-22, {@code 422 SEGREGATION_OF_DUTIES}).</li>
 *   <li>Límite {@code ADJUSTMENT_APPROVE_MAX_AMOUNT} y autorización {@code STOCK_ADJUSTMENT_APPROVAL}.</li>
 *   <li>Stock negativo bloqueado ({@code 422 STOCK_INSUFFICIENT}), rechazo y auditoría D.5.</li>
 * </ul>
 */
class StockAdjustmentIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    BusinessClock clock;

    @Test
    void createPendingDoesNotMoveStock_idempotency_costVisibility_andFilters() throws Exception {
        String sfx = uniqueSuffix();
        String storeCode = "AJ" + sfx.substring(0, 4).toUpperCase();
        Long storeA = insertStore(storeCode, "Local Ajuste A " + sfx);
        Long storeB = insertStore(TestData.uniqueStoreCode(), "Local Ajuste B " + sfx);

        Long bodId = insertUser("ajbod_" + sfx, "Pass#1234", null);
        Long supId = insertUser("ajsup_" + sfx, "Pass#1234", "7418");
        Long cajId = insertUser("ajcaj_" + sfx, "Pass#1234", null);

        assignStore(bodId, storeA, true);
        assignStore(supId, storeA, true);
        assignStore(cajId, storeA, true);

        Long bodRole = insertRoleWithLimits(
                "AJ_BOD_" + sfx,
                List.of(Permissions.INVENTORY_ADJUSTMENT_CREATE),
                Map.of());
        Long supRole = insertRoleWithLimits(
                "AJ_SUP_" + sfx,
                List.of(Permissions.INVENTORY_ADJUSTMENT_CREATE, Permissions.INVENTORY_ADJUSTMENT_APPROVE),
                Map.of("ADJUSTMENT_APPROVE_MAX_AMOUNT", new BigDecimal("50000")));
        Long cajRole = insertRoleWithLimits(
                "AJ_CAJ_" + sfx,
                List.of(Permissions.INVENTORY_STOCK_READ),
                Map.of());

        assignRole(bodId, bodRole);
        assignRole(supId, supRole);
        assignRole(cajId, cajRole);

        String bodToken = tokenFor(bodId, "ajbod_" + sfx, bodRole);
        String supToken = tokenFor(supId, "ajsup_" + sfx, supRole);
        String cajToken = tokenFor(cajId, "ajcaj_" + sfx, cajRole);

        Long catId = insertCategory("CatAj-" + sfx);
        Long prodIn = insertProduct("SKU-AI-" + sfx, "Bebida In " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("1000.0000"), false);
        Long prodOut = insertProduct("SKU-AO-" + sfx, "Cafe Out " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("2000.0000"), false);
        insertStock(storeA, prodIn, new BigDecimal("10.000"));
        insertStock(storeA, prodOut, new BigDecimal("10.000"));

        String payload = JSON.writeValueAsString(Map.of(
                "reasonCode", "COUNT_CORRECTION",
                "notes", "Diferencia detectada en estante",
                "lines", List.of(
                        Map.of("productId", prodIn, "quantityDelta", 5),
                        Map.of("productId", prodOut, "quantityDelta", -2)
                )));

        // Sin permiso inventory:adjustment:create -> 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Sin Idempotency-Key -> 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Creación válida -> 201 PENDING_APPROVAL y costos ocultos para bodeguero
        UUID idemKey = UUID.randomUUID();
        MvcResult createdRes = mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeA)
                        .header("Idempotency-Key", idemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.reasonCode").value("COUNT_CORRECTION"))
                .andExpect(jsonPath("$.requestedBy.id").value(bodId))
                .andReturn();

        JsonNode createdJson = JSON.readTree(createdRes.getResponse().getContentAsString());
        assertThat(createdJson.get("totalCostImpact").isNull()).isTrue();
        assertThat(createdJson.get("lines").get(0).get("unitCost").isNull()).isTrue();
        long adjustmentId = createdJson.get("id").asLong();
        String number = createdJson.get("number").asString();
        assertThat(number).startsWith(storeCode + "-AJ-");

        // Idempotencia: mismo Idempotency-Key + payload devuelve el mismo recurso
        mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeA)
                        .header("Idempotency-Key", idemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(adjustmentId))
                .andExpect(jsonPath("$.number").value(number));

        // PENDING_APPROVAL NO mueve stock ni escribe stock_movements
        assertThat(stockQuantity(storeA, prodIn)).isEqualByComparingTo("10.000");
        assertThat(stockQuantity(storeA, prodOut)).isEqualByComparingTo("10.000");
        assertThat(countStockMovements("STOCK_ADJUSTMENT", adjustmentId)).isZero();

        // Auditoría STOCK_ADJUSTMENT_REQUESTED registrada
        assertThat(countAuditEvents("STOCK_ADJUSTMENT_REQUESTED", String.valueOf(adjustmentId))).isEqualTo(1);

        // Excepción D.2: supervisor con inventory:adjustment:approve (sin catalog:cost:read) SÍ ve costos
        // 5 * 1000 - 2 * 2000 = +1000.00
        mvc.perform(get("/api/v1/stock-adjustments/" + adjustmentId)
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(adjustmentId))
                .andExpect(jsonPath("$.totalCostImpact").value(1000.00))
                .andExpect(jsonPath("$.lines[0].unitCost").value(1000.0000))
                .andExpect(jsonPath("$.lines[1].unitCost").value(2000.0000));

        // Local no asignado -> 403 STORE_ACCESS_DENIED
        mvc.perform(get("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

        // Listado con filtros status, from, to y sort=campo,dir
        LocalDate today = clock.businessDate();
        mvc.perform(get("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeA)
                        .param("status", "PENDING_APPROVAL")
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.plusDays(1).toString())
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + adjustmentId + ")]").isNotEmpty());

        mvc.perform(get("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeA)
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + adjustmentId + ")]").isEmpty());
    }

    @Test
    void approve_enforcesSod_roleLimit_override_stockInsufficient_andUpdatesKardex() throws Exception {
        String sfx = uniqueSuffix();
        Long storeId = insertStore(TestData.uniqueStoreCode(), "Local Aprob " + sfx);

        Long bodId = insertUser("apbod_" + sfx, "Pass#1234", null);
        Long sup1Id = insertUser("apsup1_" + sfx, "Pass#1234", "7418");
        Long sup2Id = insertUser("apsup2_" + sfx, "Pass#1234", "8529");
        Long mgrId = insertUser("apmgr_" + sfx, "Pass#1234", "9631");

        assignStore(bodId, storeId, true);
        assignStore(sup1Id, storeId, true);
        assignStore(sup2Id, storeId, true);
        assignStore(mgrId, storeId, true);

        Long bodRole = insertRoleWithLimits(
                "AP_BOD_" + sfx,
                List.of(Permissions.INVENTORY_ADJUSTMENT_CREATE),
                Map.of());
        Long supRole = insertRoleWithLimits(
                "AP_SUP_" + sfx,
                List.of(Permissions.INVENTORY_ADJUSTMENT_CREATE, Permissions.INVENTORY_ADJUSTMENT_APPROVE),
                Map.of("ADJUSTMENT_APPROVE_MAX_AMOUNT", new BigDecimal("50000")));

        // Rol gerente con ADJUSTMENT_APPROVE_MAX_AMOUNT = NULL (∞)
        Map<String, BigDecimal> unlimitedMap = new LinkedHashMap<>();
        unlimitedMap.put("ADJUSTMENT_APPROVE_MAX_AMOUNT", null);
        Long mgrRole = insertRoleWithLimits(
                "AP_MGR_" + sfx,
                List.of(Permissions.INVENTORY_ADJUSTMENT_CREATE, Permissions.INVENTORY_ADJUSTMENT_APPROVE),
                unlimitedMap);

        assignRole(bodId, bodRole);
        assignRole(sup1Id, supRole);
        assignRole(sup2Id, supRole);
        assignRole(mgrId, mgrRole);

        String bodToken = tokenFor(bodId, "apbod_" + sfx, bodRole);
        String sup1Token = tokenFor(sup1Id, "apsup1_" + sfx, supRole);
        String sup2Token = tokenFor(sup2Id, "apsup2_" + sfx, supRole);
        String mgrToken = tokenFor(mgrId, "apmgr_" + sfx, mgrRole);

        Long catId = insertCategory("CatAp-" + sfx);
        Long prodWithin = insertProduct("SKU-PW-" + sfx, "Aceite " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("5000.0000"), false);
        Long prodOver = insertProduct("SKU-PO-" + sfx, "Queso Caro " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("8000.0000"), false);
        insertStock(storeId, prodWithin, new BigDecimal("10.000"));
        insertStock(storeId, prodOver, new BigDecimal("5.000"));

        // 1. sup1 crea ajuste de +3 * 5000 = 15000 (dentro del límite de 50000)
        MvcResult withinRes = mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + sup1Token)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "reasonCode", "FOUND_GOODS",
                                "notes", "Cajas encontradas",
                                "lines", List.of(Map.of("productId", prodWithin, "quantityDelta", 3))))))
                .andExpect(status().isCreated())
                .andReturn();
        long withinId = JSON.readTree(withinRes.getResponse().getContentAsString()).get("id").asLong();

        // RN-22 SOD: sup1 intenta aprobar su propio ajuste -> 422 SEGREGATION_OF_DUTIES
        mvc.perform(post("/api/v1/stock-adjustments/" + withinId + "/approve")
                        .header("Authorization", "Bearer " + sup1Token)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));
        assertThat(stockQuantity(storeId, prodWithin)).isEqualByComparingTo("10.000");

        // sup2 aprueba dentro de su límite -> 200 APPROVED, stock sube a 13.000 y se registra kardex + auditoría
        mvc.perform(post("/api/v1/stock-adjustments/" + withinId + "/approve")
                        .header("Authorization", "Bearer " + sup2Token)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy.id").value(sup2Id))
                .andExpect(jsonPath("$.lines[0].stockMovementId").isNumber());

        assertThat(stockQuantity(storeId, prodWithin)).isEqualByComparingTo("13.000");
        assertThat(countStockMovements("STOCK_ADJUSTMENT", withinId)).isEqualTo(1);
        assertThat(countAuditEvents("STOCK_ADJUSTMENT_APPROVED", String.valueOf(withinId))).isEqualTo(1);

        // Re-aprobar o rechazar ajuste ya APPROVED -> 409 INVALID_STATE_TRANSITION
        mvc.perform(post("/api/v1/stock-adjustments/" + withinId + "/approve")
                        .header("Authorization", "Bearer " + sup2Token)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // 2. Bodeguero crea ajuste de +10 * 8000 = 80000 (> límite 50000 del supervisor)
        MvcResult overRes = mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "reasonCode", "SYSTEM_ERROR",
                                "notes", "Corrección sobre tope",
                                "lines", List.of(Map.of("productId", prodOver, "quantityDelta", 10))))))
                .andExpect(status().isCreated())
                .andReturn();
        long overId = JSON.readTree(overRes.getResponse().getContentAsString()).get("id").asLong();

        // sup2 intenta aprobar sin token -> 422 OVERRIDE_REQUIRED (action=STOCK_ADJUSTMENT_APPROVAL)
        MvcResult overrideReqRes = mvc.perform(post("/api/v1/stock-adjustments/" + overId + "/approve")
                        .header("Authorization", "Bearer " + sup2Token)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.action").value("STOCK_ADJUSTMENT_APPROVAL"))
                .andExpect(jsonPath("$.context.entityId").value(overId))
                .andReturn();

        JsonNode overrideContext = JSON.readTree(overrideReqRes.getResponse().getContentAsString()).get("context");

        // Gerente (límite ilimitado) autoriza el override con PIN vía /api/v1/auth/overrides
        MvcResult authRes = mvc.perform(post("/api/v1/auth/overrides")
                        .header("Authorization", "Bearer " + sup2Token)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "action", "STOCK_ADJUSTMENT_APPROVAL",
                                "context", overrideContext,
                                "supervisorUsername", "apmgr_" + sfx,
                                "method", "PIN",
                                "secret", "9631",
                                "reason", "Autorizo ajuste excepcional"))))
                .andExpect(status().isOk())
                .andReturn();
        String overrideToken = JSON.readTree(authRes.getResponse().getContentAsString())
                .get("overrideToken").asString();

        // sup2 reintenta con X-Override-Token -> 200 APPROVED, stock sube de 5 a 15 y audit_log tiene authorization_id
        mvc.perform(post("/api/v1/stock-adjustments/" + overId + "/approve")
                        .header("Authorization", "Bearer " + sup2Token)
                        .header("X-Store-Id", storeId)
                        .header("X-Override-Token", overrideToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(stockQuantity(storeId, prodOver)).isEqualByComparingTo("15.000");
        Long auditAuthId = ownerJdbc().sql("""
                SELECT authorization_id
                FROM audit_log
                WHERE action = 'STOCK_ADJUSTMENT_APPROVED'
                  AND entity_id = :entityId
                ORDER BY id DESC
                LIMIT 1
                """)
                .param("entityId", String.valueOf(overId))
                .query(Long.class)
                .single();
        assertThat(auditAuthId).isNotNull();

        // 3. Stock negativo al aprobar -> 422 STOCK_INSUFFICIENT sin modificar existencias
        MvcResult negRes = mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "reasonCode", "MISSING_GOODS",
                                "lines", List.of(Map.of("productId", prodOver, "quantityDelta", -99))))))
                .andExpect(status().isCreated())
                .andReturn();
        long negId = JSON.readTree(negRes.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/stock-adjustments/" + negId + "/approve")
                        .header("Authorization", "Bearer " + mgrToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));

        assertThat(stockQuantity(storeId, prodOver)).isEqualByComparingTo("15.000");
        assertThat(adjustmentStatus(negId)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void reject_and_validationErrorsWorkCorrectly() throws Exception {
        String sfx = uniqueSuffix();
        Long storeId = insertStore(TestData.uniqueStoreCode(), "Local Rech " + sfx);

        Long bodId = insertUser("rjbod_" + sfx, "Pass#1234", null);
        Long supId = insertUser("rjsup_" + sfx, "Pass#1234", null);
        assignStore(bodId, storeId, true);
        assignStore(supId, storeId, true);

        Long bodRole = insertRoleWithLimits(
                "RJ_BOD_" + sfx,
                List.of(Permissions.INVENTORY_ADJUSTMENT_CREATE),
                Map.of());
        Long supRole = insertRoleWithLimits(
                "RJ_SUP_" + sfx,
                List.of(Permissions.INVENTORY_ADJUSTMENT_CREATE, Permissions.INVENTORY_ADJUSTMENT_APPROVE),
                Map.of("ADJUSTMENT_APPROVE_MAX_AMOUNT", new BigDecimal("50000")));
        assignRole(bodId, bodRole);
        assignRole(supId, supRole);

        String bodToken = tokenFor(bodId, "rjbod_" + sfx, bodRole);
        String supToken = tokenFor(supId, "rjsup_" + sfx, supRole);

        Long catId = insertCategory("CatRj-" + sfx);
        Long unitProd = insertProduct("SKU-RU-" + sfx, "Pan Unit " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("500.0000"), false);
        Long packProd = insertProduct("SKU-RP-" + sfx, "Pack Six " + sfx, catId,
                "PACK", "UNIT", new BigDecimal("2500.0000"), false);
        Long lotProd = insertProduct("SKU-RL-" + sfx, "Yogurt Lote " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("900.0000"), true);

        insertStock(storeId, unitProd, new BigDecimal("20.000"));
        insertStock(storeId, lotProd, new BigDecimal("5.000"));

        // Validaciones de negocio en creación
        mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "reasonCode", "OTHER",
                                "lines", List.of(Map.of("productId", packProd, "quantityDelta", 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "reasonCode", "OTHER",
                                "lines", List.of(Map.of("productId", unitProd, "quantityDelta", new BigDecimal("1.5")))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "reasonCode", "LOT_CORRECTION",
                                "lines", List.of(Map.of("productId", lotProd, "quantityDelta", -1))))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOT_REQUIRED"));

        // Solicitud válida de bodeguero para probar rechazo
        MvcResult reqRes = mvc.perform(post("/api/v1/stock-adjustments")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "reasonCode", "COUNT_CORRECTION",
                                "lines", List.of(Map.of("productId", unitProd, "quantityDelta", -4))))))
                .andExpect(status().isCreated())
                .andReturn();
        long adjId = JSON.readTree(reqRes.getResponse().getContentAsString()).get("id").asLong();

        // Bodeguero sin permiso inventory:adjustment:approve intenta rechazar -> 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/stock-adjustments/" + adjId + "/reject")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"No autorizado\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Supervisor rechaza con motivo válido -> 200 REJECTED, stock intacto y auditoría
        mvc.perform(post("/api/v1/stock-adjustments/" + adjId + "/reject")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Conteo duplicado por error\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approvedBy.id").value(supId))
                .andExpect(jsonPath("$.rejectionReason").value("Conteo duplicado por error"));

        assertThat(stockQuantity(storeId, unitProd)).isEqualByComparingTo("20.000");
        assertThat(countStockMovements("STOCK_ADJUSTMENT", adjId)).isZero();
        assertThat(countAuditEvents("STOCK_ADJUSTMENT_REJECTED", String.valueOf(adjId))).isEqualTo(1);

        // No se puede aprobar un ajuste ya REJECTED -> 409 INVALID_STATE_TRANSITION
        mvc.perform(post("/api/v1/stock-adjustments/" + adjId + "/approve")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    private BigDecimal stockQuantity(Long storeId, Long productId) {
        return ownerJdbc().sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId)
                .param("p", productId)
                .query(BigDecimal.class)
                .single();
    }

    private String adjustmentStatus(Long adjustmentId) {
        return ownerJdbc().sql("SELECT status FROM stock_adjustments WHERE id = :id")
                .param("id", adjustmentId)
                .query(String.class)
                .single();
    }

    private long countStockMovements(String sourceType, long sourceId) {
        return ownerJdbc().sql("""
                SELECT COUNT(*)
                FROM stock_movements
                WHERE source_type = :st
                  AND source_id = :sid
                """)
                .param("st", sourceType)
                .param("sid", sourceId)
                .query(Long.class)
                .single();
    }

    private long countAuditEvents(String action, String entityId) {
        return ownerJdbc().sql("""
                SELECT COUNT(*)
                FROM audit_log
                WHERE action = :action
                  AND entity_type = 'stock_adjustment'
                  AND entity_id = :entityId
                """)
                .param("action", action)
                .param("entityId", entityId)
                .query(Long.class)
                .single();
    }

    private String tokenFor(Long userId, String username, Long roleId) {
        List<String> permissions = ownerJdbc().sql("""
                SELECT p.code
                FROM role_permissions rp
                JOIN permissions p ON p.id = rp.permission_id
                WHERE rp.role_id = :roleId
                """)
                .param("roleId", roleId)
                .query(String.class)
                .list();
        return jwtTokenService.issueAccessToken(userId, username, TokenScope.FULL, permissions).accessToken();
    }

    private Long insertStore(String code, String name) {
        Long companyId = ownerJdbc()
                .sql("SELECT id FROM company ORDER BY id LIMIT 1")
                .query(Long.class)
                .optional()
                .orElseGet(() -> ownerJdbc().sql("""
                        INSERT INTO company (rut, legal_name, trade_name, business_activity, address, commune, city, timezone, currency)
                        VALUES ('76123456-K', 'Minimarket Test SpA', 'Minimarket Test', 'Comercio al por menor', 'Av. Test 123', 'Santiago', 'Santiago', 'America/Santiago', 'CLP')
                        RETURNING id
                        """)
                        .query(Long.class)
                        .single());
        return ownerJdbc().sql("""
                INSERT INTO stores (company_id, code, name, address, commune, city, active)
                VALUES (:companyId, :code, :name, 'Av. Test 100', 'Santiago', 'Santiago', true)
                RETURNING id
                """)
                .param("companyId", companyId)
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private Long insertUser(String username, String password, String pin) {
        return ownerJdbc().sql("""
                INSERT INTO users (username, password_hash, pin_hash, full_name, email, active)
                VALUES (:username, :pwHash, :pinHash, :fullName, :email, true)
                RETURNING id
                """)
                .param("username", username)
                .param("pwHash", passwordEncoder.encode(password))
                .param("pinHash", pin != null ? passwordEncoder.encode(pin) : null)
                .param("fullName", "Test " + username)
                .param("email", username + "@test.local")
                .query(Long.class)
                .single();
    }

    private void assignStore(Long userId, Long storeId, boolean isDefault) {
        ownerJdbc().sql("""
                INSERT INTO user_stores (user_id, store_id, is_default)
                VALUES (:userId, :storeId, :isDefault)
                """)
                .param("userId", userId)
                .param("storeId", storeId)
                .param("isDefault", isDefault)
                .update();
    }

    private Long insertRoleWithLimits(String code, List<String> permissions, Map<String, BigDecimal> limits) {
        Long roleId = ownerJdbc().sql("""
                INSERT INTO roles (code, name, description, system)
                VALUES (:code, :name, 'Rol test T49', false)
                RETURNING id
                """)
                .param("code", code)
                .param("name", code)
                .query(Long.class)
                .single();
        for (String perm : permissions) {
            ownerJdbc().sql("""
                    INSERT INTO role_permissions (role_id, permission_id)
                    SELECT :roleId, id FROM permissions WHERE code = :perm
                    """)
                    .param("roleId", roleId)
                    .param("perm", perm)
                    .update();
        }
        for (Map.Entry<String, BigDecimal> entry : limits.entrySet()) {
            ownerJdbc().sql("""
                    INSERT INTO role_limits (role_id, limit_code, value)
                    VALUES (:roleId, :code, :val)
                    """)
                    .param("roleId", roleId)
                    .param("code", entry.getKey())
                    .param("val", entry.getValue(), Types.NUMERIC)
                    .update();
        }
        return roleId;
    }

    private void assignRole(Long userId, Long roleId) {
        ownerJdbc().sql("INSERT INTO user_roles (user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
    }

    private Long insertCategory(String name) {
        return ownerJdbc().sql("""
                INSERT INTO categories (name, active, created_at, updated_at, version)
                VALUES (:name, true, now(), now(), 0)
                RETURNING id
                """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private Long insertProduct(
            String sku,
            String name,
            Long categoryId,
            String saleType,
            String unitOfMeasure,
            BigDecimal avgCost,
            boolean lotControlled) {
        return ownerJdbc().sql("""
                INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                      price_gross, avg_cost, vat_exempt, restricted_type,
                                      lot_controlled, active, created_at, updated_at, version)
                VALUES (:sku, :name, :categoryId, :saleType, :uom,
                        1500, :avgCost, false, NULL,
                        :lotControlled, true, now(), now(), 0)
                RETURNING id
                """)
                .param("sku", sku)
                .param("name", name)
                .param("categoryId", categoryId)
                .param("saleType", saleType)
                .param("uom", unitOfMeasure)
                .param("avgCost", avgCost)
                .param("lotControlled", lotControlled)
                .query(Long.class)
                .single();
    }

    private void insertStock(Long storeId, Long productId, BigDecimal qty) {
        ownerJdbc().sql("""
                INSERT INTO stock (store_id, product_id, quantity)
                VALUES (:storeId, :productId, :qty)
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .param("qty", qty)
                .update();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
