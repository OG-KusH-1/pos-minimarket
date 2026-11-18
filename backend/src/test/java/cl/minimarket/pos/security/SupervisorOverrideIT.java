package cl.minimarket.pos.security;

import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de T26 para {@code POST /auth/overrides} y {@link SupervisorOverrideApi}:
 * <ul>
 *   <li>Consumo atómico de un solo uso ligado a solicitante, local, terminal y {@code contextHash} ({@code cartHash}).</li>
 *   <li>Vencimiento del token (5 min) → {@code OVERRIDE_INVALID}.</li>
 *   <li>Segregación de funciones (SOD) → {@code SEGREGATION_OF_DUTIES}.</li>
 *   <li>Supervisor sin permiso o sin acceso al local → {@code OVERRIDE_INVALID}.</li>
 *   <li>Límite insuficiente del supervisor → {@code OVERRIDE_LIMIT_EXCEEDED}.</li>
 *   <li>Token de otro solicitante, otro local o distinta terminal → {@code OVERRIDE_INVALID}.</li>
 *   <li>Fallos con {@code method=PASSWORD} incrementan {@code failed_attempts} y bloquean la cuenta ({@code ACCOUNT_LOCKED}).</li>
 *   <li>Fallos con {@code method=PIN} incrementan {@code pin_failed_attempts} y bloquean el PIN ({@code PIN_LOCKED}).</li>
 *   <li>Rate limit por IP y usuario objetivo ({@code RATE_LIMITED}).</li>
 * </ul>
 */
class SupervisorOverrideIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private SupervisorOverrideApi overrideApi;

    @Test
    void overrideConsumeEnforcesSingleUseRequesterStoreTerminalCartHashAndExpiry() throws Exception {
        String suffix = uniqueSuffix();
        String cashierUser = "ov_caj_" + suffix;
        String otherCashier = "ov_caj2_" + suffix;
        String supUser = "ov_sup_" + suffix;

        Long cashierId = insertUser(cashierUser, "Pass123456", null);
        Long otherCashierId = insertUser(otherCashier, "Pass123456", null);
        Long supId = insertUser(supUser, "SupPass#1", "6294");

        Long companyId = insertCompany(suffix);
        Long store1 = insertStore(companyId, "O1" + suffix.substring(0, 6).toUpperCase(), "Local 1");
        Long store2 = insertStore(companyId, "O2" + suffix.substring(0, 6).toUpperCase(), "Local 2");
        Long terminal1 = insertTerminal(store1, "TO1-" + suffix.substring(0, 4).toUpperCase());
        Long terminal2 = insertTerminal(store1, "TO2-" + suffix.substring(0, 4).toUpperCase());

        assignStore(cashierId, store1, true);
        assignStore(otherCashierId, store1, true);
        assignStore(supId, store1, true);
        assignStore(supId, store2, false);

        Long roleId = insertRoleWithLimits(
                "OV_SUP_" + suffix,
                List.of("sales:discount:override", "sales:sale:void"),
                Map.of("DISCOUNT_ITEM_MAX_PCT", new BigDecimal("15.0000"))
        );
        assignRole(supId, roleId);

        try {
            String cashierToken = jwtTokenService.issueAccessToken(
                    cashierId, cashierUser, TokenScope.POS, List.of("sales:pos:operate")).accessToken();

            String cartHash = "abc123cart";
            // Orden de claves distinto al alfabético para verificar canonicalización
            String requestBody = """
                    {
                      "action": "DISCOUNT_ITEM_OVER_LIMIT",
                      "context": {
                        "percent": 12.00,
                        "cartHash": "abc123cart",
                        "productId": 10,
                        "lineIndex": 0,
                        "amount": 500
                      },
                      "supervisorUsername": "%s",
                      "method": "PIN",
                      "secret": "6294",
                      "reason": "Cliente frecuente"
                    }
                    """.formatted(supUser);

            MvcResult res = mockMvc.perform(post("/api/v1/auth/overrides")
                            .header("Authorization", "Bearer " + cashierToken)
                            .header("X-Store-Id", String.valueOf(store1))
                            .header("X-Terminal-Id", String.valueOf(terminal1))
                            .header("X-Forwarded-For", "10.27.1.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.overrideToken").isNotEmpty())
                    .andExpect(jsonPath("$.authorizationId").isNumber())
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                    .andReturn();

            JsonNode json = JSON.readTree(res.getResponse().getContentAsString());
            String overrideToken = json.get("overrideToken").asString();
            long authId = json.get("authorizationId").asLong();

            // Contexto equivalente en Java con distinto orden de inserción y BigDecimal sin ceros
            Map<String, Object> expectedContext = new LinkedHashMap<>();
            expectedContext.put("amount", 500);
            expectedContext.put("lineIndex", 0);
            expectedContext.put("productId", 10);
            expectedContext.put("cartHash", cartHash);
            expectedContext.put("percent", new BigDecimal("12"));
            String expectedHash = overrideApi.canonicalContextHash(expectedContext);

            // 1. Otro solicitante -> OVERRIDE_INVALID
            try (var ignored = StoreContext.bind(new StoreContext(store1, terminal1))) {
                assertThatThrownBy(() -> overrideApi.consume(
                        overrideToken, OverrideAction.DISCOUNT_ITEM_OVER_LIMIT, expectedHash, otherCashierId))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.OVERRIDE_INVALID);
            }

            // 2. Otro local -> OVERRIDE_INVALID
            try (var ignored = StoreContext.bind(new StoreContext(store2, null))) {
                assertThatThrownBy(() -> overrideApi.consume(
                        overrideToken, OverrideAction.DISCOUNT_ITEM_OVER_LIMIT, expectedHash, cashierId))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.OVERRIDE_INVALID);
            }

            // 3. Otra terminal -> OVERRIDE_INVALID
            try (var ignored = StoreContext.bind(new StoreContext(store1, terminal2))) {
                assertThatThrownBy(() -> overrideApi.consume(
                        overrideToken, OverrideAction.DISCOUNT_ITEM_OVER_LIMIT, expectedHash, cashierId))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.OVERRIDE_INVALID);
            }

            // 4. cartHash distinto -> OVERRIDE_INVALID
            Map<String, Object> alteredContext = new LinkedHashMap<>(expectedContext);
            alteredContext.put("cartHash", "different_cart");
            String alteredHash = overrideApi.canonicalContextHash(alteredContext);
            try (var ignored = StoreContext.bind(new StoreContext(store1, terminal1))) {
                assertThatThrownBy(() -> overrideApi.consume(
                        overrideToken, OverrideAction.DISCOUNT_ITEM_OVER_LIMIT, alteredHash, cashierId))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.OVERRIDE_INVALID);
            }

            // 5. Consumo válido -> AuthorizationRef y actualización en BD
            try (var ignored = StoreContext.bind(new StoreContext(store1, terminal1))) {
                AuthorizationRef ref = overrideApi.consume(
                        overrideToken, OverrideAction.DISCOUNT_ITEM_OVER_LIMIT, expectedHash, cashierId, "sale", "99");
                assertThat(ref.authorizationId()).isEqualTo(authId);
                assertThat(ref.authorizedBy()).isEqualTo(supId);
                assertThat(ref.action()).isEqualTo(OverrideAction.DISCOUNT_ITEM_OVER_LIMIT);

                // 6. Un solo uso: segundo consumo falla con OVERRIDE_INVALID
                assertThatThrownBy(() -> overrideApi.consume(
                        overrideToken, OverrideAction.DISCOUNT_ITEM_OVER_LIMIT, expectedHash, cashierId))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.OVERRIDE_INVALID);
            }

            String entityType = ownerJdbc().sql("SELECT entity_type FROM supervisor_authorizations WHERE id = :id")
                    .param("id", authId).query(String.class).single();
            assertThat(entityType).isEqualTo("sale");

            // 7. Vencimiento: nuevo token expirado -> OVERRIDE_INVALID
            MvcResult res2 = mockMvc.perform(post("/api/v1/auth/overrides")
                            .header("Authorization", "Bearer " + cashierToken)
                            .header("X-Store-Id", String.valueOf(store1))
                            .header("X-Terminal-Id", String.valueOf(terminal1))
                            .header("X-Forwarded-For", "10.27.1.2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "action": "SALE_VOID",
                                      "context": {"saleId": 55},
                                      "supervisorUsername": "%s",
                                      "method": "PASSWORD",
                                      "secret": "SupPass#1"
                                    }
                                    """.formatted(supUser)))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode json2 = JSON.readTree(res2.getResponse().getContentAsString());
            String expiredToken = json2.get("overrideToken").asString();
            long authId2 = json2.get("authorizationId").asLong();
            ownerJdbc().sql("UPDATE supervisor_authorizations SET expires_at = now() - interval '1 second' WHERE id = :id")
                    .param("id", authId2).update();

            String voidHash = overrideApi.canonicalContextHash(Map.of("saleId", 55));
            try (var ignored = StoreContext.bind(new StoreContext(store1, terminal1))) {
                assertThatThrownBy(() -> overrideApi.consume(
                        expiredToken, OverrideAction.SALE_VOID, voidHash, cashierId))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.OVERRIDE_INVALID);
            }
        } finally {
            cleanupAll(List.of(cashierId, otherCashierId, supId), List.of(terminal1, terminal2), List.of(store1, store2), roleId);
        }
    }

    @Test
    void overrideRejectsSodMissingPermissionAndExceededLimitAndRecordsDenied() throws Exception {
        String suffix = uniqueSuffix();
        String cashierUser = "ov_c2_" + suffix;
        String supUser = "ov_s2_" + suffix;

        Long cashierId = insertUser(cashierUser, "Pass123456", "3847");
        Long supId = insertUser(supUser, "SupPass#1", "9274");

        Long companyId = insertCompany(suffix);
        Long storeId = insertStore(companyId, "O3" + suffix.substring(0, 6).toUpperCase(), "Local 3");
        assignStore(cashierId, storeId, true);
        assignStore(supId, storeId, true);

        Long roleId = insertRoleWithLimits(
                "OV_LIM_" + suffix,
                List.of("sales:discount:override"),
                Map.of("DISCOUNT_ITEM_MAX_PCT", new BigDecimal("15.0000"))
        );
        assignRole(supId, roleId);
        assignRole(cashierId, roleId);

        try {
            String cashierToken = jwtTokenService.issueAccessToken(
                    cashierId, cashierUser, TokenScope.POS, List.of("sales:pos:operate")).accessToken();

            // 1. SOD: solicitante == supervisor -> 422 SEGREGATION_OF_DUTIES
            mockMvc.perform(post("/api/v1/auth/overrides")
                            .header("Authorization", "Bearer " + cashierToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Forwarded-For", "10.27.2.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "action": "DISCOUNT_ITEM_OVER_LIMIT",
                                      "context": {"percent": 10, "cartHash": "c1"},
                                      "supervisorUsername": "%s",
                                      "method": "PIN",
                                      "secret": "3847"
                                    }
                                    """.formatted(cashierUser)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));

            // 2. Supervisor sin permiso de SALE_VOID -> 422 OVERRIDE_INVALID
            mockMvc.perform(post("/api/v1/auth/overrides")
                            .header("Authorization", "Bearer " + cashierToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Forwarded-For", "10.27.2.2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "action": "SALE_VOID",
                                      "context": {"saleId": 10},
                                      "supervisorUsername": "%s",
                                      "method": "PIN",
                                      "secret": "9274"
                                    }
                                    """.formatted(supUser)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("OVERRIDE_INVALID"));

            // 3. Supervisor excede límite (25% > 15%) -> 422 OVERRIDE_LIMIT_EXCEEDED
            mockMvc.perform(post("/api/v1/auth/overrides")
                            .header("Authorization", "Bearer " + cashierToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Forwarded-For", "10.27.2.3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "action": "DISCOUNT_ITEM_OVER_LIMIT",
                                      "context": {"percent": 25, "cartHash": "c2"},
                                      "supervisorUsername": "%s",
                                      "method": "PIN",
                                      "secret": "9274"
                                    }
                                    """.formatted(supUser)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("OVERRIDE_LIMIT_EXCEEDED"));

            Integer deniedCount = ownerJdbc().sql("""
                            SELECT count(*) FROM supervisor_authorizations
                            WHERE requested_by = :rid AND status = 'DENIED'
                            """)
                    .param("rid", cashierId).query(Integer.class).single();
            assertThat(deniedCount).isEqualTo(3);
        } finally {
            cleanupAll(List.of(cashierId, supId), List.of(), List.of(storeId), roleId);
        }
    }

    @Test
    void overridePasswordAndPinFailuresIncrementCountersLockAccountAndApplyRateLimit() throws Exception {
        String suffix = uniqueSuffix();
        String cashierUser = "ov_c3_" + suffix;
        String supPwdUser = "ov_sp_" + suffix;
        String supPinUser = "ov_sn_" + suffix;

        Long cashierId = insertUser(cashierUser, "Pass123456", null);
        Long supPwdId = insertUser(supPwdUser, "SupPass#1", null);
        Long supPinId = insertUser(supPinUser, "SupPass#1", "7531");

        Long companyId = insertCompany(suffix);
        Long storeId = insertStore(companyId, "O4" + suffix.substring(0, 6).toUpperCase(), "Local 4");
        assignStore(cashierId, storeId, true);
        assignStore(supPwdId, storeId, true);
        assignStore(supPinId, storeId, true);

        try {
            String cashierToken = jwtTokenService.issueAccessToken(
                    cashierId, cashierUser, TokenScope.POS, List.of("sales:pos:operate")).accessToken();

            // 5 fallos con PASSWORD -> 4 primeros 401 INVALID_CREDENTIALS, 5to 422 ACCOUNT_LOCKED
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(post("/api/v1/auth/overrides")
                                .header("Authorization", "Bearer " + cashierToken)
                                .header("X-Store-Id", String.valueOf(storeId))
                                .header("X-Forwarded-For", "10.27.3.1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "action": "SALE_VOID",
                                          "context": {"saleId": 1},
                                          "supervisorUsername": "%s",
                                          "method": "PASSWORD",
                                          "secret": "WrongPwd"
                                        }
                                        """.formatted(supPwdUser)))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            }

            mockMvc.perform(post("/api/v1/auth/overrides")
                            .header("Authorization", "Bearer " + cashierToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Forwarded-For", "10.27.3.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "action": "SALE_VOID",
                                      "context": {"saleId": 1},
                                      "supervisorUsername": "%s",
                                      "method": "PASSWORD",
                                      "secret": "WrongPwd"
                                    }
                                    """.formatted(supPwdUser)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

            Integer failedAttempts = ownerJdbc().sql("SELECT failed_attempts FROM users WHERE id = :uid")
                    .param("uid", supPwdId).query(Integer.class).single();
            assertThat(failedAttempts).isEqualTo(5);

            // 6to intento sobre el mismo usuario objetivo -> 429 RATE_LIMITED
            mockMvc.perform(post("/api/v1/auth/overrides")
                            .header("Authorization", "Bearer " + cashierToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Forwarded-For", "10.27.3.2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "action": "SALE_VOID",
                                      "context": {"saleId": 1},
                                      "supervisorUsername": "%s",
                                      "method": "PASSWORD",
                                      "secret": "SupPass#1"
                                    }
                                    """.formatted(supPwdUser)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

            // 5 fallos con PIN -> 4 primeros 401 INVALID_CREDENTIALS, 5to 422 PIN_LOCKED
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(post("/api/v1/auth/overrides")
                                .header("Authorization", "Bearer " + cashierToken)
                                .header("X-Store-Id", String.valueOf(storeId))
                                .header("X-Forwarded-For", "10.27.3.3")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "action": "SALE_VOID",
                                          "context": {"saleId": 1},
                                          "supervisorUsername": "%s",
                                          "method": "PIN",
                                          "secret": "0000"
                                        }
                                        """.formatted(supPinUser)))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            }

            mockMvc.perform(post("/api/v1/auth/overrides")
                            .header("Authorization", "Bearer " + cashierToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Forwarded-For", "10.27.3.3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "action": "SALE_VOID",
                                      "context": {"saleId": 1},
                                      "supervisorUsername": "%s",
                                      "method": "PIN",
                                      "secret": "0000"
                                    }
                                    """.formatted(supPinUser)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PIN_LOCKED"));

            Boolean pinLocked = ownerJdbc().sql("SELECT pin_locked FROM users WHERE id = :uid")
                    .param("uid", supPinId).query(Boolean.class).single();
            assertThat(pinLocked).isTrue();
        } finally {
            ownerJdbc().sql("DELETE FROM login_attempts WHERE user_id IN (:u1, :u2)")
                    .param("u1", supPwdId).param("u2", supPinId).update();
            cleanupAll(List.of(cashierId, supPwdId, supPinId), List.of(), List.of(storeId), null);
        }
    }

    private Long insertUser(String username, String rawPassword, String rawPin) {
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

    private Long insertCompany(String suffix) {
        String digits = String.format("%06d", Math.abs(suffix.hashCode()) % 1_000_000);
        ownerJdbc().sql("""
                        INSERT INTO company (rut, legal_name, trade_name, business_activity, address, commune, city, timezone)
                        VALUES (:rut, :legal, :trade, 'Comercio', 'Av. Test 1', 'Santiago', 'Santiago', 'America/Santiago')
                        ON CONFLICT DO NOTHING
                        """)
                .param("rut", "76" + digits + "-1")
                .param("legal", "Empresa " + suffix + " SpA")
                .param("trade", "Minimarket " + suffix)
                .update();
        return ownerJdbc().sql("SELECT id FROM company LIMIT 1").query(Long.class).single();
    }

    private Long insertStore(Long companyId, String code, String name) {
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

    private Long insertTerminal(Long storeId, String code) {
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

    private void assignStore(Long userId, Long storeId, boolean isDefault) {
        ownerJdbc().sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:uid, :sid, :def)")
                .param("uid", userId).param("sid", storeId).param("def", isDefault).update();
    }

    private Long insertRoleWithLimits(String roleCode, List<String> permissionCodes, Map<String, BigDecimal> limits) {
        Long roleId = ownerJdbc().sql("""
                        INSERT INTO roles (code, name, description, system)
                        VALUES (:code, :name, 'Rol test', false)
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
                    .param("rid", roleId).param("pcode", permCode).update();
        }
        for (RoleLimitCode code : RoleLimitCode.values()) {
            BigDecimal value = limits.getOrDefault(code.name(), BigDecimal.ZERO);
            ownerJdbc().sql("""
                            INSERT INTO role_limits (role_id, limit_code, value)
                            VALUES (:rid, :code, :val)
                            """)
                    .param("rid", roleId)
                    .param("code", code.name())
                    .param("val", value)
                    .update();
        }
        return roleId;
    }

    private void assignRole(Long userId, Long roleId) {
        ownerJdbc().sql("INSERT INTO user_roles (user_id, role_id) VALUES (:uid, :rid)")
                .param("uid", userId).param("rid", roleId).update();
    }

    private void cleanupAll(List<Long> userIds, List<Long> terminalIds, List<Long> storeIds, Long roleId) {
        // audit_log es append-only (trigger impide DELETE), por lo que users/supervisor_authorizations/stores con sufijo único se conservan.
        for (Long uid : userIds) {
            ownerJdbc().sql("DELETE FROM login_attempts WHERE user_id = :uid").param("uid", uid).update();
        }
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
