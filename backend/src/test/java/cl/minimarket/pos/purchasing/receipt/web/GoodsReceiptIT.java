package cl.minimarket.pos.purchasing.receipt.web;

import cl.minimarket.pos.purchasing.OpenOrdersQuery;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del ciclo completo de recepciones de mercadería
 * ({@code /api/v1/goods-receipts}, {@code T59}, {@code API.md} F.4 flujo 6,
 * {@code HU-PRV-05}, {@code HU-PRV-06}, {@code HU-PRV-07}, {@code HU-PRV-08}).
 */
class GoodsReceiptIT extends AbstractIntegrationIT {

    private static final AtomicLong RUT_SEQ = new AtomicLong(
            93_000_000L + (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 5_000_000L));

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    OpenOrdersQuery openOrdersQuery;

    private String suffix;
    private long storeId;
    private long supplierId;
    private long normalProductId;
    private long lotProductId;
    private long secondUserId;

    @BeforeEach
    void setUpData() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = insertStore("RC" + suffix.substring(0, 6).toUpperCase(), "Local RC " + suffix);
        appJdbc().sql("""
                INSERT INTO user_stores (user_id, store_id, is_default)
                VALUES (1, :storeId, false)
                ON CONFLICT (user_id, store_id) DO NOTHING
                """)
                .param("storeId", storeId)
                .update();

        secondUserId = insertUser("bod-" + suffix, "bod-" + suffix + "@minimarket.cl");
        appJdbc().sql("""
                INSERT INTO user_stores (user_id, store_id, is_default)
                VALUES (:userId, :storeId, false)
                ON CONFLICT (user_id, store_id) DO NOTHING
                """)
                .param("userId", secondUserId)
                .param("storeId", storeId)
                .update();

        long categoryId = insertCategory("Lácteos " + suffix);
        normalProductId = insertProduct("SKU-RCN-" + suffix, "Leche Entera " + suffix, categoryId, false, new BigDecimal("500.0000"));
        lotProductId = insertProduct("SKU-RCL-" + suffix, "Yogurt Lote " + suffix, categoryId, true, new BigDecimal("300.0000"));

        // Stock inicial de 10 unidades para normalProductId (HU-PRV-07: Q=10 a $500)
        appJdbc().sql("""
                UPDATE stock SET quantity = 10.000
                WHERE store_id = :storeId AND product_id = :productId
                """)
                .param("storeId", storeId)
                .param("productId", normalProductId)
                .update();

        supplierId = insertSupplier(nextValidRut(), "Proveedor RC " + suffix);
        insertProductSupplier(normalProductId, supplierId, new BigDecimal("1"), new BigDecimal("600.0000"));
        insertProductSupplier(lotProductId, supplierId, new BigDecimal("1"), new BigDecimal("350.0000"));
    }

    @Test
    void flow6_receiptDraft_lotRequired_idempotency_averageCost_andOpenOrdersQuery() throws Exception {
        // 1. Crear y enviar OC como usuario 1 (40 unidades de normalProductId y 20 de lotProductId)
        long poId = createAndSendOrder(
                "1",
                List.of(
                        Map.of("productId", normalProductId, "qtyPurchaseUnits", 40, "unitCostNet", 600),
                        Map.of("productId", lotProductId, "qtyPurchaseUnits", 20, "unitCostNet", 350)));

        // OpenOrdersQuery debe reportar 40 y 20 pendientes
        Map<Long, BigDecimal> pendingBefore = openOrdersQuery.pendingQuantities(storeId);
        assertThat(pendingBefore.get(normalProductId)).isEqualByComparingTo("40.000");
        assertThat(pendingBefore.get(lotProductId)).isEqualByComparingTo("20.000");

        // 2. GET /purchase-orders/{id}/receipt-draft prellena lo pendiente
        MvcResult draftResult = mvc.perform(get("/api/v1/purchase-orders/{id}/receipt-draft", poId)
                        .with(user("1").authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseOrderId").value(poId))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andReturn();

        JsonNode draftJson = jsonMapper.readTree(draftResult.getResponse().getContentAsString());
        long poLineNormalId = draftJson.path("lines").get(0).path("purchaseOrderLineId").asLong();
        long poLineLotId = draftJson.path("lines").get(1).path("purchaseOrderLineId").asLong();

        // 3. Crear recepción en DRAFT sin lotNumber en el producto controlado por lote, con 35 recibidas y 5 rechazadas
        // (neto = 30 aceptadas de normalProductId) y 20 de lotProductId
        String createPayload = jsonMapper.writeValueAsString(Map.of(
                "purchaseOrderId", poId,
                "supplierDocumentType", "INVOICE",
                "supplierDocumentNumber", "FAC-1001",
                "lines", List.of(
                        Map.of(
                                "purchaseOrderLineId", poLineNormalId,
                                "productId", normalProductId,
                                "qtyReceived", 35,
                                "qtyRejected", 5,
                                "differenceReason", "5 envases dañados",
                                "unitCostNet", 600),
                        Map.of(
                                "purchaseOrderLineId", poLineLotId,
                                "productId", lotProductId,
                                "qtyReceived", 20,
                                "qtyRejected", 0,
                                "unitCostNet", 350))));

        MvcResult createdRes = mvc.perform(post("/api/v1/goods-receipts")
                        .with(user(String.valueOf(secondUserId)).authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();

        JsonNode createdJson = jsonMapper.readTree(createdRes.getResponse().getContentAsString());
        long receiptId = createdJson.path("id").asLong();
        long version = createdJson.path("version").asLong();

        // 4. Confirmar sin lotNumber -> 422 LOT_REQUIRED (libera clave de idempotencia)
        String confirmKey = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/goods-receipts/{id}/confirm", receiptId)
                        .with(user(String.valueOf(secondUserId)).authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", confirmKey))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOT_REQUIRED"));

        // 5. Actualizar borrador agregando lote y vencimiento para lotProductId
        LocalDate expiry = LocalDate.now().plusDays(90);
        String updatePayload = jsonMapper.writeValueAsString(Map.of(
                "purchaseOrderId", poId,
                "supplierDocumentType", "INVOICE",
                "supplierDocumentNumber", "FAC-1001",
                "version", version,
                "lines", List.of(
                        Map.of(
                                "purchaseOrderLineId", poLineNormalId,
                                "productId", normalProductId,
                                "qtyReceived", 35,
                                "qtyRejected", 5,
                                "differenceReason", "5 envases dañados",
                                "unitCostNet", 600),
                        Map.of(
                                "purchaseOrderLineId", poLineLotId,
                                "productId", lotProductId,
                                "qtyReceived", 20,
                                "qtyRejected", 0,
                                "unitCostNet", 350,
                                "lotNumber", "LOT-" + suffix,
                                "expiryDate", expiry.toString()))));

        mvc.perform(put("/api/v1/goods-receipts/{id}", receiptId)
                        .with(user(String.valueOf(secondUserId)).authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 6. Confirmar con la misma Idempotency-Key -> 200 OK
        // HU-PRV-07: Q=10 a $500 + 30 aceptadas a $600 => avg' = 575.0000 (los 5 rechazados NO entran)
        mvc.perform(post("/api/v1/goods-receipts/{id}/confirm", receiptId)
                        .with(user(String.valueOf(secondUserId)).authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", confirmKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.netTotal").value(25000))
                .andExpect(jsonPath("$.lines[1].lotId").isNumber())
                .andExpect(jsonPath("$.costChanges[0].oldAvgCost").value(500.0))
                .andExpect(jsonPath("$.costChanges[0].newAvgCost").value(575.0));

        // 7. Reintento idempotente: no duplica movimiento ni stock
        mvc.perform(post("/api/v1/goods-receipts/{id}/confirm", receiptId)
                        .with(user(String.valueOf(secondUserId)).authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", confirmKey))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Verificar stock en BD: 10 + 30 = 40.000 (los 5 rechazados NO entraron) y 20.000 para lotProductId
        BigDecimal normalStock = appJdbc().sql("""
                SELECT quantity FROM stock WHERE store_id = :storeId AND product_id = :productId
                """)
                .param("storeId", storeId)
                .param("productId", normalProductId)
                .query(BigDecimal.class)
                .single();
        assertThat(normalStock).isEqualByComparingTo("40.000");

        BigDecimal lotStock = appJdbc().sql("""
                SELECT quantity FROM stock WHERE store_id = :storeId AND product_id = :productId
                """)
                .param("storeId", storeId)
                .param("productId", lotProductId)
                .query(BigDecimal.class)
                .single();
        assertThat(lotStock).isEqualByComparingTo("20.000");

        // Verificar costo promedio en products y price_history COST_RECEIPT
        BigDecimal avgCost = appJdbc().sql("SELECT avg_cost FROM products WHERE id = :id")
                .param("id", normalProductId)
                .query(BigDecimal.class)
                .single();
        assertThat(avgCost).isEqualByComparingTo("575.0000");

        Integer priceHistoryCount = appJdbc().sql("""
                SELECT COUNT(*) FROM price_history
                WHERE product_id = :id AND change_type = 'COST_RECEIPT'
                """)
                .param("id", normalProductId)
                .query(Integer.class)
                .single();
        assertThat(priceHistoryCount).isGreaterThanOrEqualTo(1);

        // Verificar estado de la OC: PARTIALLY_RECEIVED (quedan 10 pendientes de normalProductId)
        String poStatus = appJdbc().sql("SELECT status FROM purchase_orders WHERE id = :id")
                .param("id", poId)
                .query(String.class)
                .single();
        assertThat(poStatus).isEqualTo("PARTIALLY_RECEIVED");

        Map<Long, BigDecimal> pendingAfterPartial = openOrdersQuery.pendingQuantities(storeId);
        assertThat(pendingAfterPartial.get(normalProductId)).isEqualByComparingTo("10.000");
        assertThat(pendingAfterPartial).doesNotContainKey(lotProductId);

        // 8. Segunda recepción completando las 10 unidades pendientes -> OC pasa a RECEIVED
        String secondPayload = jsonMapper.writeValueAsString(Map.of(
                "purchaseOrderId", poId,
                "supplierDocumentType", "DISPATCH_GUIDE",
                "supplierDocumentNumber", "GD-2002",
                "lines", List.of(Map.of(
                        "purchaseOrderLineId", poLineNormalId,
                        "productId", normalProductId,
                        "qtyReceived", 10,
                        "qtyRejected", 0,
                        "unitCostNet", 600))));

        MvcResult secondCreated = mvc.perform(post("/api/v1/goods-receipts")
                        .with(user(String.valueOf(secondUserId)).authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondPayload))
                .andExpect(status().isCreated())
                .andReturn();
        long secondReceiptId = jsonMapper.readTree(secondCreated.getResponse().getContentAsString()).path("id").asLong();

        mvc.perform(post("/api/v1/goods-receipts/{id}/confirm", secondReceiptId)
                        .with(user(String.valueOf(secondUserId)).authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        String finalPoStatus = appJdbc().sql("SELECT status FROM purchase_orders WHERE id = :id")
                .param("id", poId)
                .query(String.class)
                .single();
        assertThat(finalPoStatus).isEqualTo("RECEIVED");

        Map<Long, BigDecimal> pendingAfterFull = openOrdersQuery.pendingQuantities(storeId);
        assertThat(pendingAfterFull).doesNotContainKey(normalProductId);

        // 9. Ocultamiento de costos sin catalog:cost:read
        mvc.perform(get("/api/v1/goods-receipts/{id}", receiptId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_READ)))
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netTotal").doesNotExist())
                .andExpect(jsonPath("$.lines[0].unitCostNet").doesNotExist());
    }

    @Test
    void overReceive_rejectedWithoutPermission_allowedWithPermission() throws Exception {
        long poId = createAndSendOrder(
                "1",
                List.of(Map.of("productId", normalProductId, "qtyPurchaseUnits", 5, "unitCostNet", 500)));

        String payload = jsonMapper.writeValueAsString(Map.of(
                "purchaseOrderId", poId,
                "supplierDocumentType", "INVOICE",
                "supplierDocumentNumber", "FAC-OVER",
                "lines", List.of(Map.of(
                        "productId", normalProductId,
                        "qtyReceived", 8,
                        "qtyRejected", 0,
                        "differenceReason", "Proveedor envió 3 unidades extra",
                        "unitCostNet", 500))));

        MvcResult created = mvc.perform(post("/api/v1/goods-receipts")
                        .with(user(String.valueOf(secondUserId)).authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        long receiptId = jsonMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();

        // Sin purchasing:receipt:over-receive -> 422 RECEIPT_EXCEEDS_ORDER
        mvc.perform(post("/api/v1/goods-receipts/{id}/confirm", receiptId)
                        .with(user(String.valueOf(secondUserId)).authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECEIPT_EXCEEDS_ORDER"));

        // Con purchasing:receipt:over-receive -> 200 OK
        mvc.perform(post("/api/v1/goods-receipts/{id}/confirm", receiptId)
                        .with(user(String.valueOf(secondUserId)).authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_RECEIPT_CONFIRM),
                                new SimpleGrantedAuthority(Permissions.PURCHASING_RECEIPT_OVER_RECEIVE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void withoutOrder_requiresPermission_andConfirmsWhenAuthorized() throws Exception {
        String payload = jsonMapper.writeValueAsString(Map.of(
                "supplierId", supplierId,
                "supplierDocumentType", "INVOICE",
                "supplierDocumentNumber", "FAC-NOOC",
                "lines", List.of(Map.of(
                        "productId", normalProductId,
                        "qtyReceived", 5,
                        "qtyRejected", 0,
                        "unitCostNet", 550))));

        // Sin purchasing:receipt:without-order -> 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/goods-receipts")
                        .with(user("1").authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Con purchasing:receipt:without-order -> 201 y luego confirmar 200
        MvcResult created = mvc.perform(post("/api/v1/goods-receipts")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_RECEIPT_CREATE),
                                new SimpleGrantedAuthority(Permissions.PURCHASING_RECEIPT_CONFIRM),
                                new SimpleGrantedAuthority(Permissions.PURCHASING_RECEIPT_WITHOUT_ORDER),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        long receiptId = jsonMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();

        mvc.perform(post("/api/v1/goods-receipts/{id}/confirm", receiptId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_RECEIPT_CONFIRM),
                                new SimpleGrantedAuthority(Permissions.PURCHASING_RECEIPT_WITHOUT_ORDER),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void significantDifference_enforcesSegregationOfDuties() throws Exception {
        long poId = createAndSendOrder(
                "1",
                List.of(Map.of("productId", normalProductId, "qtyPurchaseUnits", 10, "unitCostNet", 500)));

        // Diferencia del 50% (5 recibidas vs 10 esperadas)
        String payload = jsonMapper.writeValueAsString(Map.of(
                "purchaseOrderId", poId,
                "supplierDocumentType", "NONE",
                "lines", List.of(Map.of(
                        "productId", normalProductId,
                        "qtyReceived", 5,
                        "qtyRejected", 0,
                        "differenceReason", "Faltante proveedor",
                        "unitCostNet", 500))));

        MvcResult created = mvc.perform(post("/api/v1/goods-receipts")
                        .with(user("1").authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        long receiptId = jsonMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();

        // El mismo creador de la OC ("1") intenta confirmar -> 422 SEGREGATION_OF_DUTIES
        mvc.perform(post("/api/v1/goods-receipts/{id}/confirm", receiptId)
                        .with(user("1").authorities(receiptAuthorities()))
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));
    }

    private long createAndSendOrder(String creatorUserId, List<Map<String, Object>> lines) throws Exception {
        String createBody = jsonMapper.writeValueAsString(Map.of(
                "supplierId", supplierId,
                "expectedDate", LocalDate.now().plusDays(3).toString(),
                "lines", lines));

        MvcResult created = mvc.perform(post("/api/v1/purchase-orders")
                        .with(user(creatorUserId).authorities(orderAuthorities()))
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = jsonMapper.readTree(created.getResponse().getContentAsString());
        long poId = json.path("id").asLong();
        long version = json.path("version").asLong();

        mvc.perform(post("/api/v1/purchase-orders/{id}/send", poId)
                        .with(user(creatorUserId).authorities(orderAuthorities()))
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("emailToSupplier", false, "version", version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
        return poId;
    }

    private static SimpleGrantedAuthority[] receiptAuthorities() {
        return new SimpleGrantedAuthority[]{
                new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_READ),
                new SimpleGrantedAuthority(Permissions.PURCHASING_RECEIPT_CREATE),
                new SimpleGrantedAuthority(Permissions.PURCHASING_RECEIPT_CONFIRM),
                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)
        };
    }

    private static SimpleGrantedAuthority[] orderAuthorities() {
        return new SimpleGrantedAuthority[]{
                new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_READ),
                new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_CREATE),
                new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_SEND),
                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)
        };
    }

    private long insertStore(String code, String name) {
        Long companyId = ownerJdbc().sql("SELECT id FROM company ORDER BY id ASC LIMIT 1")
                .query(Long.class)
                .optional()
                .orElseGet(() -> ownerJdbc().sql("""
                        INSERT INTO company (rut, legal_name, trade_name, business_activity,
                                             address, commune, city, timezone, currency)
                        VALUES ('76123456-7', 'Minimarket Test SpA', 'Minimarket Test', 'Minimarket',
                                'Av. Test 100', 'Santiago', 'Santiago', 'America/Santiago', 'CLP')
                        RETURNING id
                        """)
                        .query(Long.class)
                        .single());
        return appJdbc().sql("""
                INSERT INTO stores (company_id, code, name, address, commune, city, active)
                VALUES (:companyId, :code, :name, 'Av. Test 123', 'Santiago', 'Santiago', true)
                RETURNING id
                """)
                .param("companyId", companyId)
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertUser(String username, String email) {
        return appJdbc().sql("""
                INSERT INTO users (username, password_hash, full_name, email, active)
                VALUES (:username, '$2a$12$dummyhashdummyhashdummyhashdummyhashdummyhashdummyha', :fullName, :email, true)
                RETURNING id
                """)
                .param("username", username)
                .param("fullName", "Usuario " + username)
                .param("email", email)
                .query(Long.class)
                .single();
    }

    private long insertCategory(String name) {
        return appJdbc().sql("""
                INSERT INTO categories (name, active)
                VALUES (:name, true)
                RETURNING id
                """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertProduct(String sku, String name, long categoryId, boolean lotControlled, BigDecimal avgCost) {
        long id = appJdbc().sql("""
                INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure, price_gross, avg_cost, vat_exempt, lot_controlled, active)
                VALUES (:sku, :name, :catId, 'UNIT', 'UNIT', 1000, :avgCost, false, :lotControlled, true)
                RETURNING id
                """)
                .param("sku", sku)
                .param("name", name)
                .param("catId", categoryId)
                .param("avgCost", avgCost)
                .param("lotControlled", lotControlled)
                .query(Long.class)
                .single();

        appJdbc().sql("""
                INSERT INTO stock (store_id, product_id, quantity)
                VALUES (:storeId, :productId, 0)
                ON CONFLICT (store_id, product_id) DO NOTHING
                """)
                .param("storeId", storeId)
                .param("productId", id)
                .update();
        return id;
    }

    private long insertSupplier(String rut, String legalName) {
        return appJdbc().sql("""
                INSERT INTO suppliers (rut, legal_name, payment_terms_type, payment_terms_days, lead_time_days, active)
                VALUES (:rut, :legalName, 'CASH_ON_DELIVERY', 0, 2, true)
                RETURNING id
                """)
                .param("rut", rut)
                .param("legalName", legalName)
                .query(Long.class)
                .single();
    }

    private void insertProductSupplier(long productId, long supplierId, BigDecimal unitsPerPurchaseUnit, BigDecimal cost) {
        appJdbc().sql("""
                INSERT INTO product_suppliers (product_id, supplier_id, purchase_unit_name, units_per_purchase_unit, cost_per_purchase_unit, min_order_qty, is_preferred, active)
                VALUES (:productId, :supplierId, 'Unidad', :units, :cost, 1, true, true)
                """)
                .param("productId", productId)
                .param("supplierId", supplierId)
                .param("units", unitsPerPurchaseUnit)
                .param("cost", cost)
                .update();
    }

    private static String nextValidRut() {
        long num = RUT_SEQ.incrementAndGet();
        long m = 0;
        long s = 1;
        long temp = num;
        while (temp != 0) {
            s = (s + (temp % 10) * (9 - (m++ % 6))) % 11;
            temp /= 10;
        }
        char dv = s != 0 ? (char) (s + 47) : 'K';
        return num + "-" + dv;
    }
}
