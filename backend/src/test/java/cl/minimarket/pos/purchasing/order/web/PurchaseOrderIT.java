package cl.minimarket.pos.purchasing.order.web;

import cl.minimarket.pos.notification.application.EmailOutboxService;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del ciclo completo de órdenes de compra ({@code /api/v1/purchase-orders},
 * {@code T58}, {@code HU-PRV-03}, {@code HU-PRV-04}).
 */
class PurchaseOrderIT extends AbstractIntegrationIT {

    private static final AtomicLong RUT_SEQ = new AtomicLong(
            91_000_000L + (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 5_000_000L));

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.33.1")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("pos.notification.scheduler-enabled", () -> "false");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    EmailOutboxService emailOutboxService;

    private String suffix;
    private long storeId;
    private long supplierId;
    private long productAId;
    private long productBId;
    private String supplierEmail;

    @BeforeEach
    void setUpData() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = insertStore("PO" + suffix.substring(0, 6).toUpperCase(), "Local OC " + suffix);
        appJdbc().sql("""
                INSERT INTO user_stores (user_id, store_id, is_default)
                VALUES (1, :storeId, false)
                ON CONFLICT (user_id, store_id) DO NOTHING
                """)
                .param("storeId", storeId)
                .update();
        long categoryId = insertCategory("Abarrotes " + suffix);
        productAId = insertProduct("SKU-POA-" + suffix, "Arroz 1kg " + suffix, categoryId, false);
        productBId = insertProduct("SKU-POB-" + suffix, "Pan Exento " + suffix, categoryId, true);

        Long vatTaxId = appJdbc().sql("SELECT id FROM taxes WHERE tax_type = 'VAT' AND active = true ORDER BY id LIMIT 1")
                .query(Long.class).optional().orElse(null);
        if (vatTaxId != null) {
            appJdbc().sql("INSERT INTO product_taxes (product_id, tax_id) VALUES (:p, :t)")
                    .param("p", productAId)
                    .param("t", vatTaxId)
                    .update();
        }

        supplierEmail = "compras-" + suffix + "@proveedor.cl";
        supplierId = insertSupplier(nextValidRut(), "Distribuidora " + suffix, supplierEmail);
        insertProductSupplier(productAId, supplierId, new BigDecimal("10"), new BigDecimal("10000"));
    }

    @Test
    void fullLifecycle_stateTransitions_costHiding_pdfAndMailpit() throws Exception {
        LocalDate expectedDate = LocalDate.now().plusDays(5);

        // Sin autenticación -> 401
        mvc.perform(get("/api/v1/purchase-orders").header("X-Store-Id", storeId))
                .andExpect(status().isUnauthorized());

        // Sin permiso purchasing:order:create -> 403
        mvc.perform(post("/api/v1/purchase-orders")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(supplierId, expectedDate, "Sin permiso", List.of(
                                Map.of("productId", productAId, "qtyPurchaseUnits", 2)
                        ), null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Crear OC en DRAFT:
        // productA: 2 cajas * 10 = 20 unid base, unitCostNet por defecto = 1000 -> lineNet = 20.000, IVA 19% = 3.800
        // productB (exento): 5 unid * 1 = 5 unid base, unitCostNet = 500 -> lineNet = 2.500, IVA = 0
        // Totales: netTotal = 22.500, taxTotal = 3.800, total = 26.300
        MvcResult createRes = mvc.perform(post("/api/v1/purchase-orders")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(supplierId, expectedDate, "Pedido semanal", List.of(
                                Map.of("productId", productAId, "qtyPurchaseUnits", 2),
                                Map.of("productId", productBId, "qtyPurchaseUnits", 5, "unitCostNet", 500)
                        ), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.netTotal").value(22500))
                .andExpect(jsonPath("$.taxTotal").value(3800))
                .andExpect(jsonPath("$.total").value(26300))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andReturn();

        JsonNode created = jsonMapper.readTree(createRes.getResponse().getContentAsString());
        long orderId = created.get("id").asLong();
        String orderNumber = created.get("number").asString();
        long version = created.get("version").asLong();

        // Actualizar en DRAFT
        MvcResult updateRes = mvc.perform(put("/api/v1/purchase-orders/" + orderId)
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(supplierId, expectedDate, "Pedido actualizado", List.of(
                                Map.of("productId", productAId, "qtyPurchaseUnits", 3)
                        ), version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netTotal").value(30000))
                .andExpect(jsonPath("$.taxTotal").value(5700))
                .andExpect(jsonPath("$.total").value(35700))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andReturn();

        long updatedVersion = jsonMapper.readTree(updateRes.getResponse().getContentAsString()).get("version").asLong();

        // Ocultamiento de costos sin catalog:cost:read (detalle y listado)
        mvc.perform(get("/api/v1/purchase-orders/" + orderId)
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netTotal").doesNotExist())
                .andExpect(jsonPath("$.taxTotal").doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.lines[0].unitCostNet").doesNotExist())
                .andExpect(jsonPath("$.lines[0].lineNetTotal").doesNotExist());

        mvc.perform(get("/api/v1/purchase-orders")
                        .header("X-Store-Id", storeId)
                        .param("q", orderNumber)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].number").value(orderNumber))
                .andExpect(jsonPath("$.content[0].total").doesNotExist());

        // Exportar PDF (application/pdf)
        MvcResult pdfRes = mvc.perform(get("/api/v1/purchase-orders/" + orderId + "/pdf")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        String pdfHeader = new String(pdfRes.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII);
        assertThat(pdfHeader).isEqualTo("%PDF-");

        // Enviar OC (DRAFT -> SENT) con emailToSupplier = true -> encola en outbox
        MvcResult sendRes = mvc.perform(post("/api/v1/purchase-orders/" + orderId + "/send")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_SEND),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "emailToSupplier", true,
                                "version", updatedVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andReturn();
        long sentVersion = jsonMapper.readTree(sendRes.getResponse().getContentAsString()).get("version").asLong();

        // Procesar outbox y verificar correo con PDF adjunto en Mailpit
        int dispatched = emailOutboxService.processPending();
        assertThat(dispatched).isGreaterThanOrEqualTo(1);

        String mailpitUrl = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages";
        HttpResponse<String> mailpitRes = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(mailpitUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(mailpitRes.statusCode()).isEqualTo(200);
        JsonNode mailpitRoot = jsonMapper.readTree(mailpitRes.body());
        String expectedSubject = "Orden de Compra " + orderNumber;
        boolean emailFound = false;
        for (JsonNode msg : mailpitRoot.path("messages")) {
            if (expectedSubject.equals(msg.path("Subject").asString())) {
                emailFound = true;
                assertThat(msg.path("Attachments").asInt()).isEqualTo(1);
                break;
            }
        }
        assertThat(emailFound).isTrue();

        // Intentar editar una OC ya SENT -> 409 INVALID_STATE_TRANSITION
        mvc.perform(put("/api/v1/purchase-orders/" + orderId)
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_CREATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(supplierId, expectedDate, "Intento tardío", List.of(
                                Map.of("productId", productAId, "qtyPurchaseUnits", 1)
                        ), sentVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // Intentar reenviar una OC ya SENT -> 409 INVALID_STATE_TRANSITION
        mvc.perform(post("/api/v1/purchase-orders/" + orderId + "/send")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_SEND)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "emailToSupplier", false,
                                "version", sentVersion))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // Cancelar OC SENT sin recepciones confirmadas -> CANCELLED
        mvc.perform(post("/api/v1/purchase-orders/" + orderId + "/cancel")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_CANCEL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("reason", "Cambio de proveedor"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Cambio de proveedor"));

        // Intentar cancelar nuevamente -> 409 INVALID_STATE_TRANSITION
        mvc.perform(post("/api/v1/purchase-orders/" + orderId + "/cancel")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_CANCEL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("reason", "Segundo intento"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // Verifica auditoría registrada
        Integer auditCount = appJdbc().sql("""
                SELECT COUNT(*) FROM audit_log
                WHERE entity_type = 'purchase_order' AND entity_id = :id
                  AND action IN ('PURCHASE_ORDER_SENT', 'PURCHASE_ORDER_CANCELLED')
                """)
                .param("id", String.valueOf(orderId))
                .query(Integer.class)
                .single();
        assertThat(auditCount).isEqualTo(2);
    }

    @Test
    void cancelOrderWithConfirmedReceiptReturns409InvalidStateTransition() throws Exception {
        LocalDate expectedDate = LocalDate.now().plusDays(3);
        MvcResult createRes = mvc.perform(post("/api/v1/purchase-orders")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_CREATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(supplierId, expectedDate, null, List.of(
                                Map.of("productId", productAId, "qtyPurchaseUnits", 1)
                        ), null)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode created = jsonMapper.readTree(createRes.getResponse().getContentAsString());
        long orderId = created.get("id").asLong();
        long version = created.get("version").asLong();

        mvc.perform(post("/api/v1/purchase-orders/" + orderId + "/send")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_SEND)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("emailToSupplier", false, "version", version))))
                .andExpect(status().isOk());

        // Inserta una recepción confirmada para la OC
        ownerJdbc().sql("""
                INSERT INTO goods_receipts (number, purchase_order_id, store_id, supplier_id, status, supplier_document_type)
                VALUES (:num, :poId, :storeId, :supId, 'CONFIRMED', 'INVOICE')
                """)
                .param("num", "L01-RC-" + suffix)
                .param("poId", orderId)
                .param("storeId", storeId)
                .param("supId", supplierId)
                .update();

        mvc.perform(post("/api/v1/purchase-orders/" + orderId + "/cancel")
                        .header("X-Store-Id", storeId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_ORDER_CANCEL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("reason", "No debe permitir"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    private String orderJson(long supplierId,
                             LocalDate expectedDate,
                             String notes,
                             List<Map<String, Object>> lines,
                             Long version) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("supplierId", supplierId);
        body.put("expectedDate", expectedDate != null ? expectedDate.toString() : null);
        body.put("notes", notes);
        body.put("lines", lines);
        body.put("version", version);
        return jsonMapper.writeValueAsString(body);
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
                VALUES (:companyId, :code, :name, 'Av. Test 100', 'Santiago', 'Santiago', true)
                RETURNING id
                """)
                .param("companyId", companyId)
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single();
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

    private long insertProduct(String sku, String name, long categoryId, boolean vatExempt) {
        return appJdbc().sql("""
                INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                      price_gross, avg_cost, last_cost, vat_exempt, active)
                VALUES (:sku, :name, :catId, 'UNIT', 'UNIT', 2000, 1000, 1000, :vatExempt, true)
                RETURNING id
                """)
                .param("sku", sku)
                .param("name", name)
                .param("catId", categoryId)
                .param("vatExempt", vatExempt)
                .query(Long.class)
                .single();
    }

    private long insertSupplier(String rut, String legalName, String email) {
        return appJdbc().sql("""
                INSERT INTO suppliers (rut, legal_name, email, payment_terms_type, payment_terms_days,
                                       lead_time_days, delivery_weekdays, active)
                VALUES (:rut, :legalName, :email, 'CREDIT', 30, 2, ARRAY[1,3]::smallint[], true)
                RETURNING id
                """)
                .param("rut", rut)
                .param("legalName", legalName)
                .param("email", email)
                .query(Long.class)
                .single();
    }

    private void insertProductSupplier(long productId, long supplierId,
                                       BigDecimal unitsPerPurchaseUnit, BigDecimal costPerPurchaseUnit) {
        appJdbc().sql("""
                INSERT INTO product_suppliers (product_id, supplier_id, purchase_unit_name,
                                               units_per_purchase_unit, cost_per_purchase_unit,
                                               min_order_qty, is_preferred, active)
                VALUES (:pid, :sid, 'Caja', :uppu, :cpu, 1, true, true)
                """)
                .param("pid", productId)
                .param("sid", supplierId)
                .param("uppu", unitsPerPurchaseUnit)
                .param("cpu", costPerPurchaseUnit)
                .update();
    }

    private static String nextValidRut() {
        long body = RUT_SEQ.incrementAndGet();
        return body + "-" + computeCheckDigit(body);
    }

    private static char computeCheckDigit(long body) {
        long n = body;
        int sum = 0;
        int factor = 2;
        while (n > 0) {
            sum += (int) ((n % 10) * factor);
            n /= 10;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int rem = 11 - (sum % 11);
        if (rem == 11) {
            return '0';
        }
        if (rem == 10) {
            return 'K';
        }
        return (char) ('0' + rem);
    }
}
