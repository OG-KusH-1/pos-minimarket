package cl.minimarket.pos.sales.web.suspended;

import cl.minimarket.pos.sales.application.suspended.SuspendedSaleExpirationJob;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración para ventas suspendidas ({@code T37}, {@code HU-POS-04}):
 * <ul>
 *   <li>Suspender, listar y retomar con aislamiento por local y terminal + borrado físico al retomar.</li>
 *   <li>Validación de límite {@code pos.suspended.max_per_terminal} → {@code 422 SUSPENDED_LIMIT_REACHED}.</li>
 *   <li>Cálculo de {@code expires_at} según {@code pos.suspended.ttl_hours} y purga por {@link SuspendedSaleExpirationJob}.</li>
 *   <li>Eliminación propia, ajena con permiso directo o con override {@code SUSPENDED_SALE_DELETE} y auditoría {@code SUSPENDED_SALE_DELETED}.</li>
 * </ul>
 */
class SuspendedSaleIT extends AbstractIntegrationIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private SuspendedSaleExpirationJob expirationJob;

    private String suffix;
    private long store1;
    private long store2;
    private long terminal1;
    private long terminal2;
    private long terminal3;
    private long cashier1Id;
    private long cashier2Id;
    private long supervisorId;
    private String supervisorUsername;
    private String cashier1Token;
    private String cashier2Token;
    private String supervisorToken;
    private long product1Id;
    private long product2Id;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long companyId = ensureCompany(suffix);

        store1 = insertStore(companyId, "S1" + suffix.substring(0, 6).toUpperCase(), "Local 1 " + suffix);
        store2 = insertStore(companyId, "S2" + suffix.substring(0, 6).toUpperCase(), "Local 2 " + suffix);
        terminal1 = insertTerminal(store1, "T1-" + suffix.substring(0, 5).toUpperCase());
        terminal2 = insertTerminal(store1, "T2-" + suffix.substring(0, 5).toUpperCase());
        terminal3 = insertTerminal(store2, "T3-" + suffix.substring(0, 5).toUpperCase());

        String cashier1User = "susp_c1_" + suffix;
        String cashier2User = "susp_c2_" + suffix;
        supervisorUsername = "susp_sup_" + suffix;

        cashier1Id = insertUser(cashier1User, "Pass123456", null);
        cashier2Id = insertUser(cashier2User, "Pass123456", null);
        supervisorId = insertUser(supervisorUsername, "SupPass#1", "7419");

        assignStore(cashier1Id, store1, true);
        assignStore(cashier1Id, store2, false);
        assignStore(cashier2Id, store1, true);
        assignStore(supervisorId, store1, true);

        long supervisorRoleId = insertRoleWithPermissions(
                "SUSP_SUP_" + suffix,
                List.of(Permissions.SALES_POS_OPERATE, Permissions.SALES_SUSPENDED_DELETE_OTHERS)
        );
        assignRole(supervisorId, supervisorRoleId);

        cashier1Token = jwtTokenService.issueAccessToken(
                cashier1Id, cashier1User, TokenScope.POS, List.of(Permissions.SALES_POS_OPERATE)
        ).accessToken();
        cashier2Token = jwtTokenService.issueAccessToken(
                cashier2Id, cashier2User, TokenScope.POS, List.of(Permissions.SALES_POS_OPERATE)
        ).accessToken();
        supervisorToken = jwtTokenService.issueAccessToken(
                supervisorId,
                supervisorUsername,
                TokenScope.POS,
                List.of(Permissions.SALES_POS_OPERATE, Permissions.SALES_SUSPENDED_DELETE_OTHERS)
        ).accessToken();

        long categoryId = insertCategory("Susp Cat " + suffix);
        product1Id = createProduct("SUS1-" + suffix, "Leche Entera " + suffix, categoryId, 1500);
        product2Id = createProduct("SUS2-" + suffix, "Pan Molde " + suffix, categoryId, 2000);
    }

    @Test
    void suspendListAndResumePreserveCartIsolateTerminalAndDeleteRow() throws Exception {
        String body = """
                {
                  "label": "  Cliente busca billetera  ",
                  "cart": {
                    "lines": [
                      { "productId": %d, "quantity": 2 },
                      {
                        "productId": %d,
                        "quantity": 1,
                        "manualDiscount": { "kind": "PERCENT", "value": 10, "reason": "Promo caja" }
                      }
                    ]
                  }
                }
                """.formatted(product1Id, product2Id);

        MvcResult createdRes = mvc.perform(post("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.label").value("Cliente busca billetera"))
                .andExpect(jsonPath("$.userName").value("Usuario susp_c1_" + suffix))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.estimatedTotal").value(4800))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        long suspendedId = jsonMapper.readTree(createdRes.getResponse().getContentAsString()).get("id").asLong();

        // Visible en terminal1, oculta en terminal2 y en store2/terminal3
        mvc.perform(get("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(suspendedId));

        mvc.perform(get("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store2))
                        .header("X-Terminal-Id", String.valueOf(terminal3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // No se puede retomar desde otra terminal
        mvc.perform(post("/api/v1/pos/suspended-sales/{id}/resume", suspendedId)
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal2)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Retomar desde la terminal original devuelve el carrito y elimina la fila
        mvc.perform(post("/api/v1/pos/suspended-sales/{id}/resume", suspendedId)
                        .header("Authorization", "Bearer " + cashier2Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(suspendedId))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.estimatedTotal").value(4800))
                .andExpect(jsonPath("$.cart.lines.length()").value(2))
                .andExpect(jsonPath("$.cart.lines[0].productId").value(product1Id))
                .andExpect(jsonPath("$.cart.lines[1].manualDiscount.kind").value("PERCENT"));

        // Un segundo intento de retomar debe fallar con 404 (eliminación física atómica)
        mvc.perform(post("/api/v1/pos/suspended-sales/{id}/resume", suspendedId)
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void enforcesMaxPerTerminalSettingWithSuspendedLimitReached() throws Exception {
        upsertStoreNumberSetting(store1, "pos.suspended.max_per_terminal", "2");

        long firstId = suspendSimpleCart(cashier1Token, store1, terminal1, "Espera 1");
        suspendSimpleCart(cashier1Token, store1, terminal1, "Espera 2");

        // Tercera en la misma terminal debe fallar con 422 SUSPENDED_LIMIT_REACHED
        mvc.perform(post("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleCartBody("Espera 3")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SUSPENDED_LIMIT_REACHED"));

        // En otra terminal del mismo local sí se permite porque el límite es por terminal
        suspendSimpleCart(cashier1Token, store1, terminal2, "Terminal 2 libre");

        // Al retomar una de terminal1 se libera el cupo
        mvc.perform(post("/api/v1/pos/suspended-sales/{id}/resume", firstId)
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isOk());

        suspendSimpleCart(cashier1Token, store1, terminal1, "Espera 3 ahora cabe");
    }

    @Test
    void computesTtlFromSettingAndScheduledJobPurgesExpiredWithoutStoreContext() throws Exception {
        upsertStoreNumberSetting(store1, "pos.suspended.ttl_hours", "3");
        upsertStoreNumberSetting(store2, "pos.suspended.ttl_hours", "2");

        MvcResult createdRes = mvc.perform(post("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleCartBody("TTL 3h")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdJson = jsonMapper.readTree(createdRes.getResponse().getContentAsString());
        long expiredInStore1Id = createdJson.get("id").asLong();
        Instant createdAt = Instant.parse(createdJson.get("createdAt").asString());
        Instant expiresAt = Instant.parse(createdJson.get("expiresAt").asString());
        assertThat(Duration.between(createdAt, expiresAt))
                .isBetween(Duration.ofHours(3).minusSeconds(5), Duration.ofHours(3));

        long activeInStore1Id = suspendSimpleCart(cashier1Token, store1, terminal1, "Vigente");
        long expiredInStore2Id = suspendSimpleCart(cashier1Token, store2, terminal3, "TTL 2h local 2");

        // Simular expiración de una fila en local 1 y otra en local 2
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        ownerJdbc().sql("UPDATE suspended_sales SET expires_at = :past WHERE id IN (:id1, :id2)")
                .param("past", Timestamp.from(past))
                .param("id1", expiredInStore1Id)
                .param("id2", expiredInStore2Id)
                .update();

        // Antes del job, los endpoints ya ocultan la venta vencida
        mvc.perform(get("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(activeInStore1Id));

        mvc.perform(post("/api/v1/pos/suspended-sales/{id}/resume", expiredInStore1Id)
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Ejecutar el job fuera de peticiones HTTP (sin StoreContext activo)
        expirationJob.expireSuspendedSales();

        Long remainingExpired = ownerJdbc()
                .sql("SELECT COUNT(*) FROM suspended_sales WHERE id IN (:id1, :id2)")
                .param("id1", expiredInStore1Id)
                .param("id2", expiredInStore2Id)
                .query(Long.class)
                .single();
        Long remainingActive = ownerJdbc()
                .sql("SELECT COUNT(*) FROM suspended_sales WHERE id = :id")
                .param("id", activeInStore1Id)
                .query(Long.class)
                .single();

        assertThat(remainingExpired).isZero();
        assertThat(remainingActive).isEqualTo(1L);
    }

    @Test
    void deleteSupportsOwnDirectOverrideForOthersAndPermissionDirectAndAudits() throws Exception {
        long ownId = suspendSimpleCart(cashier1Token, store1, terminal1, "Propia");
        long otherForOverrideId = suspendSimpleCart(cashier1Token, store1, terminal1, "Ajena override");
        long otherForSupervisorId = suspendSimpleCart(cashier1Token, store1, terminal1, "Ajena permiso");

        // 1. El creador puede eliminar su propia venta suspendida sin override
        mvc.perform(delete("/api/v1/pos/suspended-sales/{id}", ownId)
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isNoContent());

        // 2. Otro cajero sin permiso ni override recibe 422 OVERRIDE_REQUIRED con acción SUSPENDED_SALE_DELETE
        mvc.perform(delete("/api/v1/pos/suspended-sales/{id}", otherForOverrideId)
                        .header("Authorization", "Bearer " + cashier2Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.action").value("SUSPENDED_SALE_DELETE"))
                .andExpect(jsonPath("$.context.suspendedSaleId").value(otherForOverrideId));

        // 3. Solicitar token de override al supervisor y reintentar con X-Override-Token
        String overrideBody = """
                {
                  "action": "SUSPENDED_SALE_DELETE",
                  "context": { "suspendedSaleId": %d },
                  "supervisorUsername": "%s",
                  "method": "PIN",
                  "secret": "7419",
                  "reason": "Cliente abandonó la fila"
                }
                """.formatted(otherForOverrideId, supervisorUsername);

        MvcResult ovRes = mvc.perform(post("/api/v1/auth/overrides")
                        .header("Authorization", "Bearer " + cashier2Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1))
                        .header("X-Forwarded-For", "10.37.1." + (Math.abs(suffix.hashCode()) % 200 + 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overrideBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode ovJson = jsonMapper.readTree(ovRes.getResponse().getContentAsString());
        String overrideToken = ovJson.get("overrideToken").asString();
        long authorizationId = ovJson.get("authorizationId").asLong();

        mvc.perform(delete("/api/v1/pos/suspended-sales/{id}", otherForOverrideId)
                        .header("Authorization", "Bearer " + cashier2Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1))
                        .header("X-Override-Token", overrideToken))
                .andExpect(status().isNoContent());

        // Verificar auditoría con authorization_id asociado
        Long recordedAuthId = ownerJdbc().sql("""
                        SELECT authorization_id
                        FROM audit_log
                        WHERE action = 'SUSPENDED_SALE_DELETED'
                          AND entity_type = 'suspended_sale'
                          AND entity_id = :eid
                        """)
                .param("eid", String.valueOf(otherForOverrideId))
                .query(Long.class)
                .single();
        assertThat(recordedAuthId).isEqualTo(authorizationId);

        // El token ya consumido no sirve para eliminar otra venta ajena
        mvc.perform(delete("/api/v1/pos/suspended-sales/{id}", otherForSupervisorId)
                        .header("Authorization", "Bearer " + cashier2Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1))
                        .header("X-Override-Token", overrideToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERRIDE_INVALID"));

        // 4. Usuario con permiso directo sales:suspended:delete-others puede eliminar sin override
        mvc.perform(delete("/api/v1/pos/suspended-sales/{id}", otherForSupervisorId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isNoContent());
    }

    @Test
    void enforcesRequiresTerminalInvalidCustomerAndPermissions() throws Exception {
        // Sin X-Terminal-Id -> 400 VALIDATION_ERROR
        mvc.perform(get("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Cliente inexistente -> 404 NOT_FOUND
        String unknownCustomerBody = """
                {
                  "label": "Cliente inexistente",
                  "customerId": 999999999,
                  "cart": {
                    "lines": [ { "productId": %d, "quantity": 1 } ]
                  }
                }
                """.formatted(product1Id);
        mvc.perform(post("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + cashier1Token)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownCustomerBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Sin permiso sales:pos:operate -> 403 ACCESS_DENIED
        String viewerToken = jwtTokenService.issueAccessToken(
                cashier1Id, "susp_c1_" + suffix, TokenScope.POS, List.of(Permissions.SALES_SALE_READ)
        ).accessToken();
        mvc.perform(get("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + viewerToken)
                        .header("X-Store-Id", String.valueOf(store1))
                        .header("X-Terminal-Id", String.valueOf(terminal1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private long suspendSimpleCart(String token, long storeId, long terminalId, String label) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/pos/suspended-sales")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", String.valueOf(storeId))
                        .header("X-Terminal-Id", String.valueOf(terminalId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleCartBody(label)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private String simpleCartBody(String label) {
        return """
                {
                  "label": "%s",
                  "cart": {
                    "lines": [
                      { "productId": %d, "quantity": 1 }
                    ]
                  }
                }
                """.formatted(label, product1Id);
    }

    private long ensureCompany(String unique) {
        String digits = String.format("%06d", Math.abs(unique.hashCode()) % 1_000_000);
        ownerJdbc().sql("""
                        INSERT INTO company (rut, legal_name, trade_name, business_activity, address, commune, city, timezone)
                        VALUES (:rut, :legal, :trade, 'Comercio', 'Av. Test 1', 'Santiago', 'Santiago', 'America/Santiago')
                        ON CONFLICT DO NOTHING
                        """)
                .param("rut", "76" + digits + "-1")
                .param("legal", "Empresa " + unique + " SpA")
                .param("trade", "Minimarket " + unique)
                .update();
        return ownerJdbc().sql("SELECT id FROM company LIMIT 1").query(Long.class).single();
    }

    private long insertStore(long companyId, String code, String name) {
        return ownerJdbc().sql("""
                        INSERT INTO stores (company_id, code, name, address, commune, city, active)
                        VALUES (:cid, :code, :name, 'Av. Test 123', 'Santiago', 'Santiago', true)
                        RETURNING id
                        """)
                .param("cid", companyId)
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertTerminal(long storeId, String code) {
        return ownerJdbc().sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:sid, :code, :name, true)
                        RETURNING id
                        """)
                .param("sid", storeId)
                .param("code", code)
                .param("name", "Caja " + code)
                .query(Long.class)
                .single();
    }

    private long insertUser(String username, String rawPassword, String rawPin) {
        String pinHash = rawPin != null ? passwordEncoder.encode(rawPin) : null;
        return ownerJdbc().sql("""
                        INSERT INTO users (username, full_name, password_hash, pin_hash, must_change_password, active)
                        VALUES (:u, :name, :h, :ph, false, true)
                        RETURNING id
                        """)
                .param("u", username)
                .param("name", "Usuario " + username)
                .param("h", passwordEncoder.encode(rawPassword))
                .param("ph", pinHash)
                .query(Long.class)
                .single();
    }

    private void assignStore(long userId, long storeId, boolean isDefault) {
        ownerJdbc().sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:uid, :sid, :def)")
                .param("uid", userId)
                .param("sid", storeId)
                .param("def", isDefault)
                .update();
    }

    private long insertRoleWithPermissions(String roleCode, List<String> permissionCodes) {
        long roleId = ownerJdbc().sql("""
                        INSERT INTO roles (code, name, description, system)
                        VALUES (:code, :name, 'Rol test T37', false)
                        RETURNING id
                        """)
                .param("code", roleCode)
                .param("name", roleCode)
                .query(Long.class)
                .single();
        for (String permCode : permissionCodes) {
            ownerJdbc().sql("""
                            INSERT INTO role_permissions (role_id, permission_id)
                            SELECT :rid, id FROM permissions WHERE code = :pcode
                            """)
                    .param("rid", roleId)
                    .param("pcode", permCode)
                    .update();
        }
        for (RoleLimitCode code : RoleLimitCode.values()) {
            ownerJdbc().sql("""
                            INSERT INTO role_limits (role_id, limit_code, value)
                            VALUES (:rid, :code, :val)
                            """)
                    .param("rid", roleId)
                    .param("code", code.name())
                    .param("val", BigDecimal.ZERO)
                    .update();
        }
        return roleId;
    }

    private void assignRole(long userId, long roleId) {
        ownerJdbc().sql("INSERT INTO user_roles (user_id, role_id) VALUES (:uid, :rid)")
                .param("uid", userId)
                .param("rid", roleId)
                .update();
    }

    private void upsertStoreNumberSetting(long storeId, String key, String jsonNumberValue) {
        ownerJdbc().sql("""
                        INSERT INTO settings (scope, store_id, key, value, value_type)
                        VALUES ('STORE', :sid, :key, CAST(:val AS jsonb), 'NUMBER')
                        """)
                .param("sid", storeId)
                .param("key", key)
                .param("val", jsonNumberValue)
                .update();
    }

    private long insertCategory(String name) {
        return ownerJdbc().sql("INSERT INTO categories (name, active) VALUES (:name, true) RETURNING id")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long createProduct(String sku, String name, long categoryId, long priceGross) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/products")
                        .with(user(String.valueOf(cashier1Id)).authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s",
                                  "name": "%s",
                                  "categoryId": %d,
                                  "saleType": "UNIT",
                                  "unitOfMeasure": "UNIT",
                                  "vatExempt": false,
                                  "initialPriceGross": %d,
                                  "shortcutCode": null,
                                  "barcodes": []
                                }
                                """.formatted(sku, name, categoryId, priceGross)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }
}
