package cl.minimarket.pos.shrinkage.web;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del módulo de Mermas ({@code T63}, {@code HU-MER-01..03}):
 * <ul>
 *   <li>Autoaprobación solo con {@code 0 < total_cost <= SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT} (RN-21).</li>
 *   <li>Idempotencia ({@code @Idempotent}), visibilidad de costos y evidencia adjunta.</li>
 *   <li>Segregación de funciones (SOD) al aprobar y rechazar, límite {@code SHRINKAGE_APPROVE_MAX_AMOUNT} y override {@code SHRINKAGE_APPROVAL}.</li>
 *   <li>Control de lotes ({@code LOT_REQUIRED}) y descuento de stock solo al aprobar cuando {@code affects_stock=true}.</li>
 *   <li>Merma sistémica {@code TRANSFER_DIFFERENCE}: nace {@code PENDING_APPROVAL} en {@code originStoreId}, se aprueba por su {@code store_id} sin movimiento de stock y se rechaza como simple anotación.</li>
 * </ul>
 */
class ShrinkageIT extends AbstractIntegrationIT {

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
    void autoApprove_onlyWhenPositiveCostWithinLimit_idempotency_evidence_andCostVisibility() throws Exception {
        String sfx = uniqueSuffix();
        Long storeId = insertStore(TestData.uniqueStoreCode(), "Local Merma A " + sfx);

        Long bodId = insertUser("shbod_" + sfx, "Pass#1234", null);
        Long viewerId = insertUser("shview_" + sfx, "Pass#1234", null);
        assignStore(bodId, storeId, true);
        assignStore(viewerId, storeId, true);

        Long bodRole = insertRoleWithLimits(
                "SH_BOD_" + sfx,
                List.of(Permissions.SHRINKAGE_RECORD_CREATE, Permissions.SHRINKAGE_RECORD_READ),
                Map.of("SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT", new BigDecimal("10000")));
        Long viewerRole = insertRoleWithLimits(
                "SH_VIEW_" + sfx,
                List.of(Permissions.SHRINKAGE_RECORD_READ),
                Map.of());

        assignRole(bodId, bodRole);
        assignRole(viewerId, viewerRole);

        String bodToken = tokenFor(bodId, "shbod_" + sfx, bodRole);
        String viewerToken = tokenFor(viewerId, "shview_" + sfx, viewerRole);

        Long catId = insertCategory("CatSh-" + sfx);
        Long prodPriced = insertProduct("SKU-SH1-" + sfx, "Leche " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("2000.0000"), false);
        Long prodZero = insertProduct("SKU-SH0-" + sfx, "Muestra Gratis " + sfx, catId,
                "UNIT", "UNIT", BigDecimal.ZERO, false);

        insertStock(storeId, prodPriced, new BigDecimal("20.000"));
        insertStock(storeId, prodZero, new BigDecimal("10.000"));

        Long evidenceId = insertEvidenceFile(bodId, sfx);
        OffsetDateTime occurredAt = OffsetDateTime.ofInstant(clock.now().minusSeconds(60), ZoneOffset.UTC);

        // 1. 0 < total_cost (6.000) <= 10.000 -> autoaprueba, approvedBy=null, stock baja de 20 a 17
        String idemKey = UUID.randomUUID().toString();
        String autoPayload = JSON.writeValueAsString(Map.of(
                "productId", prodPriced,
                "shrinkageType", "BROKEN",
                "quantity", 3,
                "reason", "Envase roto en pasillo",
                "evidenceFileId", evidenceId,
                "responsibleUserId", bodId,
                "occurredAt", occurredAt.toString()));

        MvcResult autoRes = mvc.perform(post("/api/v1/shrinkages")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(autoPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.autoApproved").value(true))
                .andExpect(jsonPath("$.affectsStock").value(true))
                .andExpect(jsonPath("$.approvedBy").doesNotExist())
                .andExpect(jsonPath("$.evidenceUrl").value("/api/v1/files/" + evidenceId + "/content"))
                .andExpect(jsonPath("$.unitCost").doesNotExist())
                .andExpect(jsonPath("$.totalCost").doesNotExist())
                .andReturn();

        long autoId = JSON.readTree(autoRes.getResponse().getContentAsString()).get("id").asLong();
        assertThat(stockQuantity(storeId, prodPriced)).isEqualByComparingTo("17.000");

        // Replay idempotente con la misma clave -> devuelve mismo recurso sin descontar de nuevo
        mvc.perform(post("/api/v1/shrinkages")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(autoPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(autoId));

        assertThat(stockQuantity(storeId, prodPriced)).isEqualByComparingTo("17.000");

        // 2. total_cost == 0 -> NO autoaprueba (exige 0 < total_cost), queda PENDING_APPROVAL
        String zeroPayload = JSON.writeValueAsString(Map.of(
                "productId", prodZero,
                "shrinkageType", "DAMAGED",
                "quantity", 2,
                "reason", "Costo cero",
                "responsibleUserId", bodId,
                "occurredAt", occurredAt.toString()));

        mvc.perform(post("/api/v1/shrinkages")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zeroPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.autoApproved").value(false));

        assertThat(stockQuantity(storeId, prodZero)).isEqualByComparingTo("10.000");

        // 3. total_cost (20.000) > 10.000 -> queda PENDING_APPROVAL sin mover stock
        String overLimitPayload = JSON.writeValueAsString(Map.of(
                "productId", prodPriced,
                "shrinkageType", "THEFT_LOSS",
                "quantity", 10,
                "reason", "Faltante detectado",
                "responsibleUserId", bodId,
                "occurredAt", occurredAt.toString()));

        mvc.perform(post("/api/v1/shrinkages")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overLimitPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.autoApproved").value(false));

        assertThat(stockQuantity(storeId, prodPriced)).isEqualByComparingTo("17.000");

        // Listado con multi-sort y visibilidad de costos oculta para viewer
        mvc.perform(get("/api/v1/shrinkages")
                        .header("Authorization", "Bearer " + viewerToken)
                        .header("X-Store-Id", storeId)
                        .param("sort", "status,asc")
                        .param("sort", "id,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].totalCost").doesNotExist());
    }

    @Test
    void approveAndReject_sod_overrideLimit_andLotRequired() throws Exception {
        String sfx = uniqueSuffix();
        Long storeId = insertStore(TestData.uniqueStoreCode(), "Local Merma B " + sfx);

        Long bodId = insertUser("sh2bod_" + sfx, "Pass#1234", null);
        Long supId = insertUser("sh2sup_" + sfx, "Pass#1234", null);
        Long mgrId = insertUser("sh2mgr_" + sfx, "Pass#1234", "8520");
        assignStore(bodId, storeId, true);
        assignStore(supId, storeId, true);
        assignStore(mgrId, storeId, true);

        Long bodRole = insertRoleWithLimits(
                "SH2_BOD_" + sfx,
                List.of(Permissions.SHRINKAGE_RECORD_CREATE, Permissions.SHRINKAGE_RECORD_READ,
                        Permissions.SHRINKAGE_RECORD_APPROVE),
                Map.of(
                        "SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT", BigDecimal.ZERO,
                        "SHRINKAGE_APPROVE_MAX_AMOUNT", new BigDecimal("100000")));
        Long supRole = insertRoleWithLimits(
                "SH2_SUP_" + sfx,
                List.of(Permissions.SHRINKAGE_RECORD_READ, Permissions.SHRINKAGE_RECORD_APPROVE),
                Map.of("SHRINKAGE_APPROVE_MAX_AMOUNT", new BigDecimal("15000")));
        Long mgrRole = insertRoleWithLimits(
                "SH2_MGR_" + sfx,
                List.of(Permissions.SHRINKAGE_RECORD_READ, Permissions.SHRINKAGE_RECORD_APPROVE),
                Map.of()); // null en role_limits -> no, insertamos explícito NULL para ilimitado

        // Insertamos SHRINKAGE_APPROVE_MAX_AMOUNT = NULL (ilimitado) para mgrRole
        ownerJdbc().sql("INSERT INTO role_limits (role_id, limit_code, value) VALUES (:r, 'SHRINKAGE_APPROVE_MAX_AMOUNT', NULL)")
                .param("r", mgrRole)
                .update();

        assignRole(bodId, bodRole);
        assignRole(supId, supRole);
        assignRole(mgrId, mgrRole);

        String bodToken = tokenFor(bodId, "sh2bod_" + sfx, bodRole);
        String supToken = tokenFor(supId, "sh2sup_" + sfx, supRole);

        Long catId = insertCategory("CatSh2-" + sfx);
        Long prodLot = insertProduct("SKU-SHL-" + sfx, "Yogurt " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("5000.0000"), true);
        insertStock(storeId, prodLot, new BigDecimal("10.000"));
        Long lotId = insertLot(storeId, prodLot, "LOT-" + sfx, LocalDate.now().plusDays(10),
                new BigDecimal("10.000"), new BigDecimal("5000.0000"));

        OffsetDateTime occurredAt = OffsetDateTime.ofInstant(clock.now().minusSeconds(60), ZoneOffset.UTC);

        // Producto con lotes sin lotId -> 422 LOT_REQUIRED
        mvc.perform(post("/api/v1/shrinkages")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "productId", prodLot,
                                "shrinkageType", "EXPIRED",
                                "quantity", 4,
                                "responsibleUserId", bodId,
                                "occurredAt", occurredAt.toString()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOT_REQUIRED"));

        // Crear merma válida de $20.000 (4 x $5.000) -> PENDING_APPROVAL
        MvcResult createRes = mvc.perform(post("/api/v1/shrinkages")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "productId", prodLot,
                                "lotId", lotId,
                                "shrinkageType", "EXPIRED",
                                "quantity", 4,
                                "reason", "Vencido en cámara",
                                "responsibleUserId", bodId,
                                "occurredAt", occurredAt.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.totalCost").value(20000.00))
                .andReturn();
        long shrinkageId = JSON.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // SOD: quien reportó no puede aprobar ni rechazar
        mvc.perform(post("/api/v1/shrinkages/" + shrinkageId + "/approve")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));

        // supId tiene límite 15.000 y merma es 20.000 -> 422 OVERRIDE_REQUIRED
        MvcResult ovReqRes = mvc.perform(post("/api/v1/shrinkages/" + shrinkageId + "/approve")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.action").value("SHRINKAGE_APPROVAL"))
                .andReturn();
        JsonNode context = JSON.readTree(ovReqRes.getResponse().getContentAsString()).get("context");

        // Gerente autoriza override vía PIN
        MvcResult authRes = mvc.perform(post("/api/v1/auth/overrides")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "action", "SHRINKAGE_APPROVAL",
                                "context", context,
                                "supervisorUsername", "sh2mgr_" + sfx,
                                "method", "PIN",
                                "secret", "8520",
                                "reason", "Autorizo merma vencida"))))
                .andExpect(status().isOk())
                .andReturn();
        String overrideToken = JSON.readTree(authRes.getResponse().getContentAsString())
                .get("overrideToken").asString();

        // Aprobar con X-Override-Token -> 200 APPROVED, stock baja de 10 a 6 y lote baja a 6
        mvc.perform(post("/api/v1/shrinkages/" + shrinkageId + "/approve")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Override-Token", overrideToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(stockQuantity(storeId, prodLot)).isEqualByComparingTo("6.000");
        assertThat(lotQuantity(lotId)).isEqualByComparingTo("6.000");

        // Segunda merma rechazada -> 200 REJECTED sin mover stock
        MvcResult create2Res = mvc.perform(post("/api/v1/shrinkages")
                        .header("Authorization", "Bearer " + bodToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "productId", prodLot,
                                "lotId", lotId,
                                "shrinkageType", "DAMAGED",
                                "quantity", 1,
                                "responsibleUserId", bodId,
                                "occurredAt", occurredAt.toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        long sh2Id = JSON.readTree(create2Res.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/shrinkages/" + sh2Id + "/reject")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("reason", "Error de conteo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Error de conteo"));

        assertThat(stockQuantity(storeId, prodLot)).isEqualByComparingTo("6.000");
    }

    @Test
    void systemicTransferDifference_pendingOnOriginStore_approvedWithoutStockMovement_andRejectedAsAnnotation()
            throws Exception {
        String sfx = uniqueSuffix();
        Long originStore = insertStore(TestData.uniqueStoreCode(), "Origen " + sfx);
        Long destStore = insertStore(TestData.uniqueStoreCode(), "Destino " + sfx);

        Long senderReceiverId = insertUser("trusr_" + sfx, "Pass#1234", null);
        Long originSupId = insertUser("trsup_" + sfx, "Pass#1234", null);
        assignStore(senderReceiverId, originStore, true);
        assignStore(senderReceiverId, destStore, false);
        assignStore(originSupId, originStore, true);
        assignStore(originSupId, destStore, false);

        Long trRole = insertRoleWithLimits(
                "TR_ROLE_" + sfx,
                List.of(
                        Permissions.INVENTORY_TRANSFER_READ,
                        Permissions.INVENTORY_TRANSFER_SEND,
                        Permissions.INVENTORY_TRANSFER_RECEIVE,
                        Permissions.SHRINKAGE_RECORD_READ),
                Map.of());
        Long supRole = insertRoleWithLimits(
                "TR_SUP_" + sfx,
                List.of(Permissions.SHRINKAGE_RECORD_READ, Permissions.SHRINKAGE_RECORD_APPROVE),
                Map.of("SHRINKAGE_APPROVE_MAX_AMOUNT", new BigDecimal("500000")));

        assignRole(senderReceiverId, trRole);
        assignRole(originSupId, supRole);

        String senderToken = tokenFor(senderReceiverId, "trusr_" + sfx, trRole);
        String supToken = tokenFor(originSupId, "trsup_" + sfx, supRole);

        Long catId = insertCategory("CatTrSh-" + sfx);
        Long prodA = insertProduct("SKU-TRS1-" + sfx, "Aceite " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("2000.0000"), false);
        Long prodB = insertProduct("SKU-TRS2-" + sfx, "Arroz " + sfx, catId,
                "UNIT", "UNIT", new BigDecimal("1500.0000"), false);

        insertStock(originStore, prodA, new BigDecimal("20.000"));
        insertStock(originStore, prodB, new BigDecimal("20.000"));
        insertStock(destStore, prodA, BigDecimal.ZERO);
        insertStock(destStore, prodB, BigDecimal.ZERO);

        // Crear y enviar transferencia de origen -> destino (5 de A, 4 de B)
        MvcResult createTr = mvc.perform(post("/api/v1/stock-transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("X-Store-Id", originStore)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "destinationStoreId", destStore,
                                "lines", List.of(
                                        Map.of("productId", prodA, "qtySent", 5),
                                        Map.of("productId", prodB, "qtySent", 4))))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode trNode = JSON.readTree(createTr.getResponse().getContentAsString());
        long transferId = trNode.get("id").asLong();
        long lineAId = trNode.get("lines").get(0).get("id").asLong();
        long lineBId = trNode.get("lines").get(1).get("id").asLong();

        mvc.perform(post("/api/v1/stock-transfers/" + transferId + "/send")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("X-Store-Id", originStore)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        // Recibir en destino con faltantes (A: 3 de 5, B: 3 de 4)
        mvc.perform(post("/api/v1/stock-transfers/" + transferId + "/receive")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("X-Store-Id", destStore)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "lines", List.of(
                                        Map.of("lineId", lineAId, "qtyReceived", 3, "differenceReason", "2 botellas rotas"),
                                        Map.of("lineId", lineBId, "qtyReceived", 3, "differenceReason", "1 bolsa rota"))))))
                .andExpect(status().isOk());

        // Las mermas sistémicas nacen PENDING_APPROVAL en originStore
        MvcResult listOrigin = mvc.perform(get("/api/v1/shrinkages")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", originStore)
                        .param("shrinkageType", "TRANSFER_DIFFERENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.content[0].affectsStock").value(false))
                .andExpect(jsonPath("$.content[0].autoApproved").value(false))
                .andReturn();

        JsonNode items = JSON.readTree(listOrigin.getResponse().getContentAsString()).get("content");
        long shAId = items.get(0).get("id").asLong();
        long shBId = items.get(1).get("id").asLong();

        // Intentar aprobar desde destStore -> 404 NOT_FOUND (solo se aprueba por el store_id de la merma = originStore)
        mvc.perform(post("/api/v1/shrinkages/" + shAId + "/approve")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", destStore))
                .andExpect(status().isNotFound());

        BigDecimal originAStockBefore = stockQuantity(originStore, prodA);
        BigDecimal originBStockBefore = stockQuantity(originStore, prodB);

        // Aprobar en originStore -> 200 APPROVED, SIN movimiento de stock
        mvc.perform(post("/api/v1/shrinkages/" + shAId + "/approve")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", originStore))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.affectsStock").value(false));

        assertThat(stockQuantity(originStore, prodA)).isEqualByComparingTo(originAStockBefore);
        Integer shAMovementCount = ownerJdbc().sql("""
                SELECT COUNT(*) FROM stock_movements
                WHERE source_type = 'SHRINKAGE' AND source_id = :id
                """)
                .param("id", shAId)
                .query(Integer.class)
                .single();
        assertThat(shAMovementCount).isZero();

        // Rechazar la segunda merma sistémica -> simple anotación sin movimiento de stock
        mvc.perform(post("/api/v1/shrinkages/" + shBId + "/reject")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", originStore)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("reason", "Apareció en camión"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Apareció en camión"));

        assertThat(stockQuantity(originStore, prodB)).isEqualByComparingTo(originBStockBefore);
    }

    private BigDecimal stockQuantity(Long storeId, Long productId) {
        return ownerJdbc().sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId)
                .param("p", productId)
                .query(BigDecimal.class)
                .single();
    }

    private BigDecimal lotQuantity(Long lotId) {
        return ownerJdbc().sql("SELECT quantity FROM lots WHERE id = :id")
                .param("id", lotId)
                .query(BigDecimal.class)
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
                VALUES (:code, :name, 'Rol test T63', false)
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

    private Long insertLot(
            Long storeId,
            Long productId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal qty,
            BigDecimal unitCost) {
        return ownerJdbc().sql("""
                INSERT INTO lots (store_id, product_id, lot_number, expiry_date, quantity, unit_cost, received_at, created_at, updated_at, version)
                VALUES (:storeId, :productId, :lotNumber, :expiryDate, :qty, :unitCost, now(), now(), now(), 0)
                RETURNING id
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .param("lotNumber", lotNumber)
                .param("expiryDate", expiryDate)
                .param("qty", qty)
                .param("unitCost", unitCost)
                .query(Long.class)
                .single();
    }

    private Long insertEvidenceFile(Long createdBy, String sfx) {
        String sha = ("0".repeat(56) + sfx).substring(0, 64);
        return ownerJdbc().sql("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256, category, created_by)
                VALUES (:key, 'evidencia.jpg', 'image/jpeg', 1024, :sha, 'SHRINKAGE_EVIDENCE', :createdBy)
                RETURNING id
                """)
                .param("key", "evidence-" + sfx + "-" + UUID.randomUUID())
                .param("sha", sha)
                .param("createdBy", createdBy)
                .query(Long.class)
                .single();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
