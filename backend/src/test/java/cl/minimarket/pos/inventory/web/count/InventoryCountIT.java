package cl.minimarket.pos.inventory.web.count;

import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.application.count.CountType;
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
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del ciclo completo de tomas físicas de inventario (T50, HU-INV-06, RN-20).
 */
class InventoryCountIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    StockApi stockApi;

    @Autowired
    TransactionTemplate tx;

    private String suffix;
    private long starterId;
    private long counterId;
    private long approverId;
    private long storeA;
    private long storeB;
    private long parentCatId;
    private long childCatId;
    private long otherCatId;
    private long prodA;
    private long prodB;
    private long prodC;
    private long prodZeroChild;
    private long prodInactive;
    private long prodPack;
    private long prodOtherCat;

    @BeforeEach
    void setUpData() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        starterId = TestData.insertUser(ownerJdbc());
        counterId = TestData.insertUser(ownerJdbc());
        approverId = TestData.insertUser(ownerJdbc());

        storeA = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        storeB = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());

        assignStoreToUser(starterId, storeA);
        assignStoreToUser(counterId, storeA);
        assignStoreToUser(approverId, storeA);
        assignStoreToUser(starterId, storeB);

        parentCatId = insertCategory("Lacteos-" + suffix, null);
        childCatId = insertCategory("Yogures-" + suffix, parentCatId);
        otherCatId = insertCategory("Abarrotes-" + suffix, null);

        prodA = insertProduct("SKU-A-" + suffix, "Leche Entera " + suffix, parentCatId, "UNIT", "UNIT",
                new BigDecimal("1000.0000"), true);
        prodB = insertProduct("SKU-B-" + suffix, "Yogurt Batido " + suffix, childCatId, "UNIT", "UNIT",
                new BigDecimal("500.0000"), true);
        prodC = insertProduct("SKU-C-" + suffix, "Queso Fresco " + suffix, parentCatId, "WEIGHT", "KG",
                new BigDecimal("4000.0000"), true);
        prodZeroChild = insertProduct("SKU-Z-" + suffix, "Yogurt Cero " + suffix, childCatId, "UNIT", "UNIT",
                new BigDecimal("450.0000"), true);
        prodInactive = insertProduct("SKU-I-" + suffix, "Leche Descontinuada " + suffix, parentCatId, "UNIT", "UNIT",
                new BigDecimal("900.0000"), false);
        prodPack = insertProduct("SKU-P-" + suffix, "Pack Leche x6 " + suffix, parentCatId, "PACK", "UNIT",
                new BigDecimal("5500.0000"), true);
        prodOtherCat = insertProduct("SKU-O-" + suffix, "Arroz Grado 1 " + suffix, otherCatId, "UNIT", "UNIT",
                new BigDecimal("1200.0000"), true);

        insertStock(storeA, prodA, new BigDecimal("20.000"));
        insertStock(storeA, prodB, new BigDecimal("10.000"));
        insertStock(storeA, prodC, new BigDecimal("5.000"));
        insertStock(storeA, prodZeroChild, new BigDecimal("0.000"));
        insertStock(storeA, prodInactive, new BigDecimal("0.000"));
        insertStock(storeA, prodOtherCat, new BigDecimal("0.000"));

        insertStock(storeB, prodA, new BigDecimal("7.000"));
    }

    @Test
    void createCyclic_includesSubcategories_excludesPacksAndInactive_andPreventsDuplicateScope() throws Exception {
        String body = """
                {
                  "countType": "CYCLIC",
                  "categoryId": %d,
                  "blind": true,
                  "notes": "Cíclica lácteos"
                }
                """.formatted(parentCatId);

        MvcResult createRes = mvc.perform(post("/api/v1/inventory-counts")
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_MANAGE),
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.countType").value("CYCLIC"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.blind").value(true))
                .andExpect(jsonPath("$.category.id").value(parentCatId))
                .andExpect(jsonPath("$.progress.totalLines").value(4))
                .andExpect(jsonPath("$.progress.countedLines").value(0))
                .andReturn();

        JsonNode created = jsonMapper.readTree(createRes.getResponse().getContentAsString());
        long countId = created.get("id").asLong();
        assertThat(created.get("number").asString()).contains("-TI-");

        // Misma tienda + misma categoría abierta → 409 DUPLICATED_VALUE
        mvc.perform(post("/api/v1/inventory-counts")
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // Otro local con la misma categoría sí se permite
        mvc.perform(post("/api/v1/inventory-counts")
                        .header("X-Store-Id", storeB)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Verificar que las líneas incluyen prodA, prodB, prodC, prodZeroChild (excluyen pack, inactivo y otra cat)
        MvcResult linesRes = mvc.perform(get("/api/v1/inventory-counts/{id}/lines", countId)
                        .header("X-Store-Id", storeA)
                        .param("size", "20")
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode lines = jsonMapper.readTree(linesRes.getResponse().getContentAsString()).get("content");
        assertThat(lines.size()).isEqualTo(4);
        assertThat(lines).extracting(n -> n.get("productId").asLong())
                .containsExactlyInAnyOrder(prodA, prodB, prodC, prodZeroChild);
    }

    @Test
    void blindCount_hidesTheoreticalAndDifferenceForExecutor_revealsForApprover() throws Exception {
        long countId = createCountViaApi(storeA, CountType.CYCLIC, parentCatId, true);

        // Ejecutor sin INVENTORY_COUNT_APPROVE ve theoreticalQty=null y difference=null antes y después de contar
        MvcResult listBefore = mvc.perform(get("/api/v1/inventory-counts/{id}/lines", countId)
                        .header("X-Store-Id", storeA)
                        .param("q", "Leche Entera")
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn();

        JsonNode lineBefore = jsonMapper.readTree(listBefore.getResponse().getContentAsString())
                .get("content").get(0);
        assertThat(lineBefore.path("theoreticalQty").isNull()).isTrue();
        assertThat(lineBefore.path("difference").isNull()).isTrue();
        long lineId = lineBefore.get("id").asLong();

        // PUT /lines como ejecutor ciego también oculta theoreticalQty y difference en la respuesta
        String putBody = """
                { "lines": [ { "lineId": %d, "countedQty": 17 } ] }
                """.formatted(lineId);
        MvcResult putRes = mvc.perform(put("/api/v1/inventory-counts/{id}/lines", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode updatedLine = jsonMapper.readTree(putRes.getResponse().getContentAsString()).get(0);
        assertThat(updatedLine.get("countedQty").decimalValue()).isEqualByComparingTo("17.000");
        assertThat(updatedLine.path("theoreticalQty").isNull()).isTrue();
        assertThat(updatedLine.path("difference").isNull()).isTrue();

        // Cabecera también oculta differenceSummary para quien no aprueba
        MvcResult headerForCounter = mvc.perform(get("/api/v1/inventory-counts/{id}", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(jsonMapper.readTree(headerForCounter.getResponse().getContentAsString())
                .path("differenceSummary").isNull()).isTrue();

        // Supervisor con INVENTORY_COUNT_APPROVE + CATALOG_COST_READ sí ve theoreticalQty, difference y differenceSummary
        MvcResult listForApprover = mvc.perform(get("/api/v1/inventory-counts/{id}/lines", countId)
                        .header("X-Store-Id", storeA)
                        .param("counted", "true")
                        .with(user(String.valueOf(approverId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE),
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_APPROVE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn();

        JsonNode visibleLine = jsonMapper.readTree(listForApprover.getResponse().getContentAsString())
                .get("content").get(0);
        assertThat(visibleLine.get("theoreticalQty").decimalValue()).isEqualByComparingTo("20.000");
        assertThat(visibleLine.get("difference").decimalValue()).isEqualByComparingTo("-3.000");
        assertThat(visibleLine.get("unitCost").decimalValue()).isEqualByComparingTo("1000.0000");

        MvcResult headerForApprover = mvc.perform(get("/api/v1/inventory-counts/{id}", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(approverId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE),
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_APPROVE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode diffSummary = jsonMapper.readTree(headerForApprover.getResponse().getContentAsString())
                .get("differenceSummary");
        assertThat(diffSummary.get("lines").asLong()).isEqualTo(1L);
        assertThat(diffSummary.get("valueOut").decimalValue()).isEqualByComparingTo("3000.00");
    }

    @Test
    void approve_preservesSalesDuringCount_appliesDeltaOnly_enforcesSodAndAudits() throws Exception {
        // FULL solo incluye prodA (20), prodB (10), prodC (5)
        long countId = createCountViaApi(storeA, CountType.FULL, null, false);

        Map<Long, Long> lineIdByProduct = fetchLineIdsByProduct(countId);
        assertThat(lineIdByProduct).containsOnlyKeys(prodA, prodB, prodC);

        // Registrar conteo: A=18 (-2), B=13 (+3), C=5 (0)
        String countBody = jsonMapper.writeValueAsString(Map.of("lines", List.of(
                Map.of("lineId", lineIdByProduct.get(prodA), "countedQty", new BigDecimal("18")),
                Map.of("lineId", lineIdByProduct.get(prodB), "countedQty", new BigDecimal("13")),
                Map.of("lineId", lineIdByProduct.get(prodC), "countedQty", new BigDecimal("5.000"))
        )));
        mvc.perform(put("/api/v1/inventory-counts/{id}/lines", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(countBody))
                .andExpect(status().isOk());

        // Simular ventas ocurridas durante la toma (después de PUT /lines y antes de aprobar, RN-20)
        tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                storeA,
                StockMovementType.SALE,
                StockSourceType.SALE,
                7001L,
                counterId,
                "Venta concurrente durante toma",
                List.of(
                        StockLine.of(prodA, new BigDecimal("4.000")),
                        StockLine.of(prodB, new BigDecimal("2.000"))))));

        // Enviar a revisión
        mvc.perform(post("/api/v1/inventory-counts/{id}/submit", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_MANAGE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // SOD: quien inició no puede aprobar
        mvc.perform(post("/api/v1/inventory-counts/{id}/approve", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_APPROVE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));

        // SOD: quien contó líneas no puede aprobar
        mvc.perform(post("/api/v1/inventory-counts/{id}/approve", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_APPROVE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));

        // Supervisor distinto aprueba → aplica deltas: A (20 - 4 sale - 2 delta = 14), B (10 - 2 sale + 3 delta = 11), C (5)
        mvc.perform(post("/api/v1/inventory-counts/{id}/approve", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(approverId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_APPROVE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy.id").value(approverId))
                .andExpect(jsonPath("$.differenceSummary.lines").value(2));

        assertThat(stockApi.onHand(storeA, prodA)).isEqualByComparingTo("14.000");
        assertThat(stockApi.onHand(storeA, prodB)).isEqualByComparingTo("11.000");
        assertThat(stockApi.onHand(storeA, prodC)).isEqualByComparingTo("5.000");

        // Verificar movimientos generados en kardex
        List<Map<String, Object>> movements = ownerJdbc().sql("""
                SELECT product_id, movement_type, quantity_delta
                FROM stock_movements
                WHERE source_type = 'INVENTORY_COUNT' AND source_id = :countId
                ORDER BY product_id
                """)
                .param("countId", countId)
                .query()
                .listOfRows();
        assertThat(movements).hasSize(2);

        // Verificar auditoría INVENTORY_COUNT_APPROVED
        long auditCount = ownerJdbc().sql("""
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'INVENTORY_COUNT_APPROVED'
                  AND entity_type = 'inventory_count'
                  AND entity_id = :entityId
                """)
                .param("entityId", String.valueOf(countId))
                .query(Long.class)
                .single();
        assertThat(auditCount).isEqualTo(1L);
    }

    @Test
    void approve_rollsBackWhenConcurrentSaleLeavesInsufficientStock() throws Exception {
        long countId = createCountViaApi(storeA, CountType.FULL, null, false);
        Map<Long, Long> lineIdByProduct = fetchLineIdsByProduct(countId);

        // A: 20 -> contado 12 (delta = -8), B: 10 -> 10, C: 5 -> 5
        String countBody = jsonMapper.writeValueAsString(Map.of("lines", List.of(
                Map.of("lineId", lineIdByProduct.get(prodA), "countedQty", new BigDecimal("12")),
                Map.of("lineId", lineIdByProduct.get(prodB), "countedQty", new BigDecimal("10")),
                Map.of("lineId", lineIdByProduct.get(prodC), "countedQty", new BigDecimal("5"))
        )));
        mvc.perform(put("/api/v1/inventory-counts/{id}/lines", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(countBody))
                .andExpect(status().isOk());

        // Venta posterior de 15 unidades deja solo 5 en stock A → delta -8 excede 5
        tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                storeA,
                StockMovementType.SALE,
                StockSourceType.SALE,
                7002L,
                counterId,
                "Venta fuerte",
                List.of(StockLine.of(prodA, new BigDecimal("15.000"))))));

        mvc.perform(post("/api/v1/inventory-counts/{id}/submit", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_MANAGE))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/inventory-counts/{id}/approve", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(approverId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_APPROVE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));

        assertThat(stockApi.onHand(storeA, prodA)).isEqualByComparingTo("5.000");
        mvc.perform(get("/api/v1/inventory-counts/{id}", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(approverId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void submitRejectCancel_andStoreAndPermissionChecks() throws Exception {
        long countId = createCountViaApi(storeA, CountType.FULL, null, false);
        Map<Long, Long> lineIdByProduct = fetchLineIdsByProduct(countId);

        // Submit con líneas sin contar → 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/inventory-counts/{id}/submit", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_MANAGE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Contar todas las líneas
        String countBody = jsonMapper.writeValueAsString(Map.of("lines", List.of(
                Map.of("lineId", lineIdByProduct.get(prodA), "countedQty", new BigDecimal("20")),
                Map.of("lineId", lineIdByProduct.get(prodB), "countedQty", new BigDecimal("10")),
                Map.of("lineId", lineIdByProduct.get(prodC), "countedQty", new BigDecimal("5"))
        )));
        mvc.perform(put("/api/v1/inventory-counts/{id}/lines", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(countBody))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/inventory-counts/{id}/submit", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_MANAGE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // Reject devuelve a OPEN y audita INVENTORY_COUNT_REJECTED
        mvc.perform(post("/api/v1/inventory-counts/{id}/reject", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(approverId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_APPROVE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Revisar pasillo queso\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.rejectionReason").value("Revisar pasillo queso"));

        long rejectAudit = ownerJdbc().sql("""
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'INVENTORY_COUNT_REJECTED' AND entity_id = :entityId
                """)
                .param("entityId", String.valueOf(countId))
                .query(Long.class)
                .single();
        assertThat(rejectAudit).isEqualTo(1L);

        // Permiso insuficiente → 403
        mvc.perform(post("/api/v1/inventory-counts/{id}/cancel", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"No autorizado\"}"))
                .andExpect(status().isForbidden());

        // Local sin acceso para counterId → 403 STORE_ACCESS_DENIED
        mvc.perform(get("/api/v1/inventory-counts/{id}", countId)
                        .header("X-Store-Id", storeB)
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

        // Local incorrecto (con acceso para starterId) → 404 (no pertenece a storeB)
        mvc.perform(get("/api/v1/inventory-counts/{id}", countId)
                        .header("X-Store-Id", storeB)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE))))
                .andExpect(status().isNotFound());

        // Cancelar libera alcance FULL para abrir otra toma en el mismo local
        mvc.perform(post("/api/v1/inventory-counts/{id}/cancel", countId)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Se reprograma toma\"}"))
                .andExpect(status().isNoContent());

        long newCountId = createCountViaApi(storeA, CountType.FULL, null, false);
        assertThat(newCountId).isNotEqualTo(countId);
    }

    private long createCountViaApi(long storeId, CountType type, Long categoryId, boolean blind) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("countType", type.name());
        payload.put("categoryId", categoryId);
        payload.put("blind", blind);
        MvcResult res = mvc.perform(post("/api/v1/inventory-counts")
                        .header("X-Store-Id", storeId)
                        .with(user(String.valueOf(starterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private Map<Long, Long> fetchLineIdsByProduct(long countId) throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/inventory-counts/{id}/lines", countId)
                        .header("X-Store-Id", storeA)
                        .param("size", "50")
                        .with(user(String.valueOf(counterId)).authorities(
                                new SimpleGrantedAuthority(Permissions.INVENTORY_COUNT_EXECUTE))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = jsonMapper.readTree(res.getResponse().getContentAsString()).get("content");
        Map<Long, Long> byProduct = new LinkedHashMap<>();
        for (JsonNode item : content) {
            byProduct.put(item.get("productId").asLong(), item.get("id").asLong());
        }
        return byProduct;
    }

    private void assignStoreToUser(long uid, long sid) {
        ownerJdbc().sql("""
                INSERT INTO user_stores (user_id, store_id, is_default)
                SELECT :userId, :storeId,
                       NOT EXISTS (SELECT 1 FROM user_stores WHERE user_id = :userId AND is_default)
                ON CONFLICT (user_id, store_id) DO NOTHING
                """)
                .param("userId", uid)
                .param("storeId", sid)
                .update();
    }

    private long insertCategory(String name, Long parentId) {
        return ownerJdbc().sql("""
                INSERT INTO categories (parent_id, name, active, created_at, updated_at, version)
                VALUES (:parentId, :name, true, now(), now(), 0)
                RETURNING id
                """)
                .param("parentId", parentId, Types.BIGINT)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertProduct(
            String sku,
            String name,
            long catId,
            String saleType,
            String uom,
            BigDecimal avgCost,
            boolean active) {
        return ownerJdbc().sql("""
                INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                      price_gross, avg_cost, vat_exempt, restricted_type,
                                      lot_controlled, active, created_at, updated_at, version)
                VALUES (:sku, :name, :categoryId, :saleType, :uom,
                        1500, :avgCost, false, NULL,
                        false, :active, now(), now(), 0)
                RETURNING id
                """)
                .param("sku", sku)
                .param("name", name)
                .param("categoryId", catId)
                .param("saleType", saleType)
                .param("uom", uom)
                .param("avgCost", avgCost)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    private void insertStock(long sid, long pid, BigDecimal qty) {
        ownerJdbc().sql("""
                INSERT INTO stock (store_id, product_id, quantity, min_qty, max_qty)
                VALUES (:storeId, :productId, :qty, 0, 100)
                """)
                .param("storeId", sid)
                .param("productId", pid)
                .param("qty", qty)
                .update();
    }
}
