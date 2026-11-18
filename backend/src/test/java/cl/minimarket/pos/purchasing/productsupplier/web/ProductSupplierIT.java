package cl.minimarket.pos.purchasing.productsupplier.web;

import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de producto-proveedor y {@link ProductSupplierApi} ({@code T57},
 * {@code HU-PRV-02}).
 */
class ProductSupplierIT extends AbstractIntegrationIT {

    private static final AtomicLong RUT_SEQ = new AtomicLong(
            81_000_000L + (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 7_000_000L));

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    ProductSupplierApi productSupplierApi;

    private String suffix;
    private long unitProductId;
    private long secondProductId;
    private long packProductId;
    private long supplierAId;
    private long supplierBId;
    private String supplierARut;

    @BeforeEach
    void setUpData() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long categoryId = insertCategory("Bebestibles " + suffix);
        unitProductId = insertProduct("SKU-U-" + suffix, "Bebida Cola " + suffix, categoryId, "UNIT");
        secondProductId = insertProduct("SKU-U2-" + suffix, "Agua Mineral " + suffix, categoryId, "UNIT");
        packProductId = insertProduct("SKU-P-" + suffix, "Pack 6 Cola " + suffix, categoryId, "PACK");
        insertPack(packProductId, unitProductId, 6);

        supplierARut = nextValidRut();
        supplierAId = insertSupplier(supplierARut, "Proveedor Alfa " + suffix, 2);
        supplierBId = insertSupplier(nextValidRut(), "Proveedor Beta " + suffix, 4);
    }

    @Test
    void productSupplierLifecycle_preferredUniqueness_packRejected_andAuditRecorded() throws Exception {
        // Sin permiso purchasing:product-supplier:manage -> 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/product-suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(psJson(unitProductId, supplierAId, "SUP-A", "Caja",
                                new BigDecimal("12"), new BigDecimal("6000"), BigDecimal.ONE, 2, true, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Producto PACK rechazado con 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/product-suppliers")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(psJson(packProductId, supplierAId, "SUP-P", "Pack",
                                BigDecimal.ONE, new BigDecimal("6000"), BigDecimal.ONE, null, false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Alta válida (caja de 12 a $6.000 -> unitCostNet = 500.0000), preferido = true
        MvcResult createARes = mvc.perform(post("/api/v1/product-suppliers")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(psJson(unitProductId, supplierAId, "SUP-A1", "Caja 12",
                                new BigDecimal("12"), new BigDecimal("6000"), BigDecimal.ONE, 1, true, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(unitProductId))
                .andExpect(jsonPath("$.supplierId").value(supplierAId))
                .andExpect(jsonPath("$.costPerPurchaseUnit").value(6000))
                .andExpect(jsonPath("$.unitCostNet").value(500))
                .andExpect(jsonPath("$.isPreferred").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long psAId = jsonMapper.readTree(createARes.getResponse().getContentAsString()).get("id").asLong();

        // Verifica auditoría PRODUCT_SUPPLIER_CHANGED de creación (incluye costo)
        AuditRow createAudit = latestAudit(psAId);
        assertThat(createAudit.action()).isEqualTo("PRODUCT_SUPPLIER_CHANGED");
        assertThat(createAudit.entityType()).isEqualTo("product_supplier");
        assertThat(createAudit.oldJson()).isNull();
        JsonNode createNewNode = jsonMapper.readTree(createAudit.newJson());
        assertThat(createNewNode.get("costPerPurchaseUnit").decimalValue()).isEqualByComparingTo("6000");
        assertThat(createNewNode.get("unitCostNet").decimalValue()).isEqualByComparingTo("500");
        assertThat(createNewNode.get("isPreferred").asBoolean()).isTrue();

        // Duplicado (mismo producto + proveedor) -> 409 DUPLICATED_VALUE
        mvc.perform(post("/api/v1/product-suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(psJson(unitProductId, supplierAId, "DUP", "Unidad",
                                BigDecimal.ONE, new BigDecimal("500"), BigDecimal.ONE, null, false, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // Alta segundo proveedor B con isPreferred = true -> desmarca al preferido anterior A
        MvcResult createBRes = mvc.perform(post("/api/v1/product-suppliers")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(psJson(unitProductId, supplierBId, "SUP-B1", "Display 6",
                                new BigDecimal("6"), new BigDecimal("2700"), new BigDecimal("2"), 3, true, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unitCostNet").value(450))
                .andExpect(jsonPath("$.isPreferred").value(true))
                .andReturn();
        long psBId = jsonMapper.readTree(createBRes.getResponse().getContentAsString()).get("id").asLong();

        // A quedó desmarcado automáticamente y solo B está marcado como preferido
        assertThat(isPreferredInDb(psAId)).isFalse();
        assertThat(isPreferredInDb(psBId)).isTrue();

        // PUT /product-suppliers/{id}/preferred sobre A -> desmarca B y audita PRODUCT_SUPPLIER_CHANGED
        mvc.perform(put("/api/v1/product-suppliers/{id}/preferred", psAId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE))))
                .andExpect(status().isNoContent());

        assertThat(isPreferredInDb(psAId)).isTrue();
        assertThat(isPreferredInDb(psBId)).isFalse();

        AuditRow preferredAudit = latestAudit(psAId);
        assertThat(jsonMapper.readTree(preferredAudit.oldJson()).get("isPreferred").asBoolean()).isFalse();
        assertThat(jsonMapper.readTree(preferredAudit.newJson()).get("isPreferred").asBoolean()).isTrue();

        // PUT /product-suppliers/{id} con versión previa (0, ya incrementada a 1 al desmarcar preferido) -> 409
        long versionB = jsonMapper.readTree(createBRes.getResponse().getContentAsString()).get("version").asLong();
        mvc.perform(put("/api/v1/product-suppliers/{id}", psBId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(psJson(unitProductId, supplierBId, "SUP-B2", "Display 6",
                                new BigDecimal("6"), new BigDecimal("3000"), new BigDecimal("2"), 2, false, versionB)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        // PUT /product-suppliers/{id} con versión vigente (versionB + 1) actualiza costo y audita antes y después
        mvc.perform(put("/api/v1/product-suppliers/{id}", psBId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(psJson(unitProductId, supplierBId, "SUP-B2", "Display 6",
                                new BigDecimal("6"), new BigDecimal("3000"), new BigDecimal("2"), 2, false, versionB + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costPerPurchaseUnit").value(3000))
                .andExpect(jsonPath("$.unitCostNet").value(500))
                .andExpect(jsonPath("$.version").value(versionB + 2));

        AuditRow updateAudit = latestAudit(psBId);
        assertThat(jsonMapper.readTree(updateAudit.oldJson()).get("costPerPurchaseUnit").decimalValue())
                .isEqualByComparingTo("2700");
        assertThat(jsonMapper.readTree(updateAudit.newJson()).get("costPerPurchaseUnit").decimalValue())
                .isEqualByComparingTo("3000");

        // Desactivar A -> active=false e isPreferred=false, auditado; intentar marcar preferido inactivo -> 409
        mvc.perform(post("/api/v1/product-suppliers/{id}/deactivate", psAId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE))))
                .andExpect(status().isNoContent());
        assertThat(isPreferredInDb(psAId)).isFalse();

        mvc.perform(put("/api/v1/product-suppliers/{id}/preferred", psAId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // Reactivar A -> active=true, auditado
        mvc.perform(post("/api/v1/product-suppliers/{id}/activate", psAId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE))))
                .andExpect(status().isNoContent());

        AuditRow reactivateAudit = latestAudit(psAId);
        assertThat(jsonMapper.readTree(reactivateAudit.oldJson()).get("active").asBoolean()).isFalse();
        assertThat(jsonMapper.readTree(reactivateAudit.newJson()).get("active").asBoolean()).isTrue();
    }

    @Test
    void queriesHideCostsWithoutPermissionAndSupportPaginationAndSort() throws Exception {
        // Crea 2 productos asociados al proveedor A y 1 al proveedor B
        mvc.perform(post("/api/v1/product-suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(psJson(unitProductId, supplierAId, "A-COLA", "Caja 12",
                                new BigDecimal("12"), new BigDecimal("6000"), BigDecimal.ONE, null, true, null)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/product-suppliers")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_PRODUCT_SUPPLIER_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(psJson(secondProductId, supplierAId, "A-AGUA", "Pack 6",
                                new BigDecimal("6"), new BigDecimal("2400"), BigDecimal.ONE, null, false, null)))
                .andExpect(status().isCreated());

        // Sin catalog:cost:read -> costPerPurchaseUnit y unitCostNet llegan null
        mvc.perform(get("/api/v1/suppliers/{id}/products", supplierAId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].costPerPurchaseUnit").isEmpty())
                .andExpect(jsonPath("$.content[0].unitCostNet").isEmpty());

        mvc.perform(get("/api/v1/products/{id}/suppliers", unitProductId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].costPerPurchaseUnit").isEmpty())
                .andExpect(jsonPath("$[0].unitCostNet").isEmpty());

        // Con catalog:cost:read y sort=costPerPurchaseUnit,asc -> Agua ($2400) primero, Cola ($6000) segundo
        MvcResult sortedByCost = mvc.perform(get("/api/v1/suppliers/{id}/products", supplierAId)
                        .param("sort", "costPerPurchaseUnit,asc")
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].costPerPurchaseUnit").value(2400))
                .andExpect(jsonPath("$.content[0].unitCostNet").value(400))
                .andExpect(jsonPath("$.content[1].costPerPurchaseUnit").value(6000))
                .andExpect(jsonPath("$.content[1].unitCostNet").value(500))
                .andReturn();

        JsonNode content = jsonMapper.readTree(sortedByCost.getResponse().getContentAsString()).get("content");
        List<Long> prodIds = StreamSupport.stream(content.spliterator(), false)
                .map(n -> n.get("productId").asLong())
                .toList();
        assertThat(prodIds).containsExactly(secondProductId, unitProductId);

        // Con catalog:cost:read en GET /products/{id}/suppliers
        mvc.perform(get("/api/v1/products/{id}/suppliers", unitProductId)
                        .with(user("1").authorities(
                                new SimpleGrantedAuthority(Permissions.PURCHASING_SUPPLIER_READ),
                                new SimpleGrantedAuthority(Permissions.CATALOG_COST_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].costPerPurchaseUnit").value(6000))
                .andExpect(jsonPath("$[0].unitCostNet").value(500));
    }

    @Test
    void productSupplierApi_upsertAndQueriesWorkAcrossStates() {
        // Lookup de proveedor por RUT formateado
        assertThat(productSupplierApi.findSupplierByRut(formatWithDots(supplierARut)))
                .isPresent()
                .get()
                .satisfies(ref -> assertThat(ref.id()).isEqualTo(supplierAId));

        // Upsert rechaza PACK con 400 VALIDATION_ERROR
        assertThatThrownBy(() -> productSupplierApi.upsert(new ProductSupplierApi.ProductSupplierCommand(
                packProductId, supplierAId, null, "Pack",
                BigDecimal.ONE, new BigDecimal("5000"), BigDecimal.ONE, null, false)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        // Upsert crea asociación nueva con proveedor B
        ProductSupplierApi.ProductSupplierInfo createdB = productSupplierApi.upsert(
                new ProductSupplierApi.ProductSupplierCommand(
                        unitProductId, supplierBId, "SKU-B", "Display 10",
                        new BigDecimal("10"), new BigDecimal("4500"), new BigDecimal("2"), 5, false));
        assertThat(createdB.unitCostNet()).isEqualByComparingTo("450.0000");
        assertThat(createdB.isPreferred()).isFalse();

        // Upsert crea asociación preferida con proveedor A
        ProductSupplierApi.ProductSupplierInfo createdA = productSupplierApi.upsert(
                new ProductSupplierApi.ProductSupplierCommand(
                        unitProductId, supplierAId, "SKU-A", "Caja 12",
                        new BigDecimal("12"), new BigDecimal("6000"), BigDecimal.ONE, 1, true));
        assertThat(createdA.isPreferred()).isTrue();

        // Upsert actualiza proveedor B a preferido (desmarca A) y conserva supplierSku si viene null
        ProductSupplierApi.ProductSupplierInfo updatedB = productSupplierApi.upsert(
                new ProductSupplierApi.ProductSupplierCommand(
                        unitProductId, supplierBId, null, "Display 10",
                        new BigDecimal("10"), new BigDecimal("4200"), new BigDecimal("3"), null, true));
        assertThat(updatedB.id()).isEqualTo(createdB.id());
        assertThat(updatedB.supplierSku()).isEqualTo("SKU-B");
        assertThat(updatedB.leadTimeDaysOverride()).isEqualTo(5);
        assertThat(updatedB.unitCostNet()).isEqualByComparingTo("420.0000");
        assertThat(updatedB.isPreferred()).isTrue();
        assertThat(productSupplierApi.find(unitProductId, supplierAId).orElseThrow().isPreferred()).isFalse();

        // candidates() devuelve primero el preferido B y luego A
        List<ProductSupplierApi.ProductSupplierCandidate> candidates = productSupplierApi.candidates(unitProductId);
        assertThat(candidates).extracting(ProductSupplierApi.ProductSupplierCandidate::supplierId)
                .containsExactly(supplierBId, supplierAId);
        assertThat(candidates.getFirst().effectiveLeadTimeDays()).isEqualTo(5);
        assertThat(candidates.get(1).effectiveLeadTimeDays()).isEqualTo(1);
    }

    private boolean isPreferredInDb(long psId) {
        return Boolean.TRUE.equals(appJdbc()
                .sql("SELECT is_preferred FROM product_suppliers WHERE id = :id")
                .param("id", psId)
                .query(Boolean.class)
                .single());
    }

    private AuditRow latestAudit(long psId) {
        return ownerJdbc().sql("""
                SELECT action, entity_type, old_value::text AS old_json, new_value::text AS new_json
                FROM audit_log
                WHERE action = 'PRODUCT_SUPPLIER_CHANGED'
                  AND entity_type = 'product_supplier'
                  AND entity_id = :entityId
                ORDER BY id DESC
                LIMIT 1
                """)
                .param("entityId", String.valueOf(psId))
                .query((rs, rowNum) -> new AuditRow(
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("old_json"),
                        rs.getString("new_json")))
                .single();
    }

    private record AuditRow(String action, String entityType, String oldJson, String newJson) {
    }

    private String psJson(long productId,
                          long supplierId,
                          String supplierSku,
                          String purchaseUnitName,
                          BigDecimal unitsPerPurchaseUnit,
                          BigDecimal costPerPurchaseUnit,
                          BigDecimal minOrderQty,
                          Integer leadTimeDaysOverride,
                          Boolean isPreferred,
                          Long version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId);
        body.put("supplierId", supplierId);
        body.put("supplierSku", supplierSku);
        body.put("purchaseUnitName", purchaseUnitName);
        body.put("unitsPerPurchaseUnit", unitsPerPurchaseUnit);
        body.put("costPerPurchaseUnit", costPerPurchaseUnit);
        body.put("minOrderQty", minOrderQty);
        body.put("leadTimeDaysOverride", leadTimeDaysOverride);
        body.put("isPreferred", isPreferred);
        if (version != null) {
            body.put("version", version);
        }
        return jsonMapper.writeValueAsString(body);
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

    private long insertProduct(String sku, String name, long categoryId, String saleType) {
        return ownerJdbc().sql("""
                INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                      price_gross, avg_cost, vat_exempt, lot_controlled,
                                      active, created_at, updated_at, version)
                VALUES (:sku, :name, :categoryId, :saleType, 'UNIT',
                        1200, 0.0000, false, false,
                        true, now(), now(), 0)
                RETURNING id
                """)
                .param("sku", sku)
                .param("name", name)
                .param("categoryId", categoryId)
                .param("saleType", saleType)
                .query(Long.class)
                .single();
    }

    private void insertPack(long packProductId, long baseProductId, int factor) {
        ownerJdbc().sql("""
                INSERT INTO product_packs (product_id, base_product_id, factor, created_at, updated_at, version)
                VALUES (:packId, :baseId, :factor, now(), now(), 0)
                """)
                .param("packId", packProductId)
                .param("baseId", baseProductId)
                .param("factor", factor)
                .update();
    }

    private long insertSupplier(String rut, String legalName, int leadTimeDays) {
        return ownerJdbc().sql("""
                INSERT INTO suppliers (rut, legal_name, payment_terms_type, payment_terms_days,
                                       lead_time_days, delivery_weekdays, active, created_at, updated_at, version)
                VALUES (:rut, :legalName, 'CREDIT', 30,
                        :leadTimeDays, '{1,4}'::smallint[], true, now(), now(), 0)
                RETURNING id
                """)
                .param("rut", rut)
                .param("legalName", legalName)
                .param("leadTimeDays", leadTimeDays)
                .query(Long.class)
                .single();
    }

    private static String nextValidRut() {
        long body = RUT_SEQ.incrementAndGet();
        return body + "-" + computeDv(body);
    }

    private static String formatWithDots(String normalizedRut) {
        String[] parts = normalizedRut.split("-");
        String digits = parts[0];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append('.');
            }
            sb.append(digits.charAt(i));
        }
        return sb + "-" + parts[1];
    }

    private static char computeDv(long body) {
        int sum = 0;
        int factor = 2;
        for (long rest = body; rest > 0; rest /= 10) {
            sum += (int) (rest % 10) * factor;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int dv = 11 - (sum % 11);
        if (dv == 11) {
            return '0';
        }
        return dv == 10 ? 'K' : (char) ('0' + dv);
    }
}
