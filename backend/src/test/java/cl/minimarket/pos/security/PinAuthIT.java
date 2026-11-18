package cl.minimarket.pos.security;

import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de T26 para PIN personal y login por PIN:
 * <ul>
 *   <li>{@code PUT /auth/me/pin}: valida contraseña actual, rechaza PIN débil (iguales o secuencia),
 *       guarda hash y reinicia {@code pin_failed_attempts}.</li>
 *   <li>{@code POST /auth/pin-login}: exige sesión vigente ligada a la terminal ({@code TERMINAL_INVALID}),
 *       emite token {@code scope=POS} restringido a {@code sales:*}, {@code cash:*}, {@code customers:*},
 *       bloquea el PIN tras 5 fallos ({@code PIN_LOCKED}), se auto-desbloquea pasado
 *       {@code security.pin.lockout_minutes} y aplica rate limit ({@code RATE_LIMITED}).</li>
 * </ul>
 */
class PinAuthIT extends AbstractIntegrationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void changePinValidatesPasswordAndStrengthAndResetsAttempts() throws Exception {
        String suffix = uniqueSuffix();
        String username = "pin_cfg_" + suffix;
        Long userId = insertUser(username, "SecretPass#1", null);
        ownerJdbc().sql("UPDATE users SET pin_failed_attempts = 3 WHERE id = :uid")
                .param("uid", userId).update();

        try {
            String token = jwtTokenService.issueAccessToken(
                    userId, username, TokenScope.FULL, List.of()).accessToken();

            // Contraseña actual incorrecta -> 401 INVALID_CREDENTIALS
            mockMvc.perform(put("/api/v1/auth/me/pin")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"WrongPass#1\",\"newPin\":\"4829\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

            // PIN todos iguales -> 400 VALIDATION_ERROR
            mockMvc.perform(put("/api/v1/auth/me/pin")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"SecretPass#1\",\"newPin\":\"1111\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            // PIN secuencia ascendente -> 400 VALIDATION_ERROR
            mockMvc.perform(put("/api/v1/auth/me/pin")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"SecretPass#1\",\"newPin\":\"12345\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            // PIN secuencia descendente -> 400 VALIDATION_ERROR
            mockMvc.perform(put("/api/v1/auth/me/pin")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"SecretPass#1\",\"newPin\":\"654321\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            // PIN válido -> 204 y reinicia pin_failed_attempts
            mockMvc.perform(put("/api/v1/auth/me/pin")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"SecretPass#1\",\"newPin\":\"4829\"}"))
                    .andExpect(status().isNoContent());

            Integer attempts = ownerJdbc().sql("SELECT pin_failed_attempts FROM users WHERE id = :uid")
                    .param("uid", userId).query(Integer.class).single();
            String pinHash = ownerJdbc().sql("SELECT pin_hash FROM users WHERE id = :uid")
                    .param("uid", userId).query(String.class).single();
            assertThat(attempts).isZero();
            assertThat(passwordEncoder.matches("4829", pinHash)).isTrue();
        } finally {
            ownerJdbc().sql("DELETE FROM users WHERE id = :uid").param("uid", userId).update();
        }
    }

    @Test
    void pinLoginRequiresBoundTerminalAndIssuesPosScopeToken() throws Exception {
        String suffix = uniqueSuffix();
        String baseUser = "base_pos_" + suffix;
        String cashierUser = "cash_pos_" + suffix;
        Long baseUserId = insertUser(baseUser, "SecretPass#1", null);
        Long cashierUserId = insertUser(cashierUser, "SecretPass#1", "7391");

        Long companyId = insertCompany(suffix);
        Long storeId = insertStore(companyId, "P1" + suffix.substring(0, 6).toUpperCase(), "Local POS");
        Long terminal1 = insertTerminal(storeId, "TP1-" + suffix.substring(0, 4).toUpperCase());
        Long terminal2 = insertTerminal(storeId, "TP2-" + suffix.substring(0, 4).toUpperCase());

        assignStore(baseUserId, storeId);
        assignStore(cashierUserId, storeId);
        Long roleId = insertRoleWithPermissions("ROLE_POS_" + suffix,
                List.of("sales:pos:operate", "cash:session:open", "customers:customer:read", "security:user:read"));
        assignRole(cashierUserId, roleId);

        try {
            // Login inicial del usuario base (sin terminal ligada aún)
            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Forwarded-For", "10.26.1.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + baseUser + "\",\"password\":\"SecretPass#1\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            Cookie refreshCookie = loginResult.getResponse().getCookie("pos_refresh");
            assertThat(refreshCookie).isNotNull();

            String baseToken = jwtTokenService.issueAccessToken(
                    baseUserId, baseUser, TokenScope.FULL, List.of()).accessToken();

            // Sin terminal ligada en la sesión -> 422 TERMINAL_INVALID
            mockMvc.perform(post("/api/v1/auth/pin-login")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Terminal-Id", String.valueOf(terminal1))
                            .header("X-Forwarded-For", "10.26.1.2")
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + cashierUser + "\",\"pin\":\"7391\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("TERMINAL_INVALID"));

            // Ligar sesión a terminal1
            mockMvc.perform(post("/api/v1/auth/session/terminal")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"terminalId\":" + terminal1 + "}"))
                    .andExpect(status().isNoContent());

            // Intentar pin-login desde terminal2 -> 422 TERMINAL_INVALID
            mockMvc.perform(post("/api/v1/auth/pin-login")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Terminal-Id", String.valueOf(terminal2))
                            .header("X-Forwarded-For", "10.26.1.3")
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + cashierUser + "\",\"pin\":\"7391\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("TERMINAL_INVALID"));

            // pin-login correcto en terminal1 -> 200 scope=POS y sin security:user:read
            MvcResult pinResult = mockMvc.perform(post("/api/v1/auth/pin-login")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Terminal-Id", String.valueOf(terminal1))
                            .header("X-Forwarded-For", "10.26.1.4")
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + cashierUser + "\",\"pin\":\"7391\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope").value("POS"))
                    .andExpect(jsonPath("$.user.scope").value("POS"))
                    .andExpect(jsonPath("$.user.hasPin").value(true))
                    .andExpect(jsonPath("$.user.permissions").isArray())
                    .andReturn();

            String body = pinResult.getResponse().getContentAsString();
            assertThat(body).contains("sales:pos:operate");
            assertThat(body).contains("cash:session:open");
            assertThat(body).contains("customers:customer:read");
            assertThat(body).doesNotContain("security:user:read");

            String posToken = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(body).get("accessToken").asString();

            // El token POS no puede acceder a /users -> 403 ACCESS_DENIED
            mockMvc.perform(get("/api/v1/users")
                            .header("Authorization", "Bearer " + posToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        } finally {
            cleanupSessionAndUsers(List.of(baseUserId, cashierUserId), List.of(terminal1, terminal2), storeId, roleId);
        }
    }

    @Test
    void pinLocksAfterMaxAttemptsAndAutoUnlocksAfterLockoutMinutes() throws Exception {
        String suffix = uniqueSuffix();
        String baseUser = "base_lk_" + suffix;
        String targetUser = "pin_lk_" + suffix;
        String autoUnlockUser = "pin_auto_" + suffix;

        Long baseUserId = insertUser(baseUser, "SecretPass#1", null);
        Long targetUserId = insertUser(targetUser, "SecretPass#1", "5917");
        Long autoUnlockUserId = insertUser(autoUnlockUser, "SecretPass#1", "8342");

        Long companyId = insertCompany(suffix);
        Long storeId = insertStore(companyId, "L1" + suffix.substring(0, 6).toUpperCase(), "Local Lock");
        Long terminalId = insertTerminal(storeId, "TL-" + suffix.substring(0, 4).toUpperCase());

        assignStore(baseUserId, storeId);
        assignStore(targetUserId, storeId);
        assignStore(autoUnlockUserId, storeId);

        try {
            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Forwarded-For", "10.26.2.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + baseUser + "\",\"password\":\"SecretPass#1\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            Cookie refreshCookie = loginResult.getResponse().getCookie("pos_refresh");
            String baseToken = jwtTokenService.issueAccessToken(
                    baseUserId, baseUser, TokenScope.FULL, List.of()).accessToken();

            mockMvc.perform(post("/api/v1/auth/session/terminal")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"terminalId\":" + terminalId + "}"))
                    .andExpect(status().isNoContent());

            // 4 intentos fallidos -> 401 INVALID_CREDENTIALS
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(post("/api/v1/auth/pin-login")
                                .header("Authorization", "Bearer " + baseToken)
                                .header("X-Store-Id", String.valueOf(storeId))
                                .header("X-Terminal-Id", String.valueOf(terminalId))
                                .header("X-Forwarded-For", "10.26.2.2")
                                .cookie(refreshCookie)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"" + targetUser + "\",\"pin\":\"9999\"}"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            }

            // 5to intento fallido -> 422 PIN_LOCKED
            mockMvc.perform(post("/api/v1/auth/pin-login")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Terminal-Id", String.valueOf(terminalId))
                            .header("X-Forwarded-For", "10.26.2.2")
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + targetUser + "\",\"pin\":\"9999\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PIN_LOCKED"));

            Boolean pinLocked = ownerJdbc().sql("SELECT pin_locked FROM users WHERE id = :uid")
                    .param("uid", targetUserId).query(Boolean.class).single();
            assertThat(pinLocked).isTrue();

            Integer auditPinLocked = ownerJdbc().sql("""
                            SELECT count(*) FROM audit_log
                            WHERE action = 'AUTH_PIN_LOCKED' AND entity_id = :eid
                            """)
                    .param("eid", String.valueOf(targetUserId))
                    .query(Integer.class).single();
            assertThat(auditPinLocked).isEqualTo(1);

            // Usuario con PIN bloqueado cuyo pin_locked_until aún no vence -> 422 PIN_LOCKED
            ownerJdbc().sql("""
                            UPDATE users
                            SET pin_locked = true,
                                pin_failed_attempts = 5,
                                pin_locked_until = now() + interval '10 minutes'
                            WHERE id = :uid
                            """)
                    .param("uid", autoUnlockUserId).update();

            mockMvc.perform(post("/api/v1/auth/pin-login")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Terminal-Id", String.valueOf(terminalId))
                            .header("X-Forwarded-For", "10.26.2.3")
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + autoUnlockUser + "\",\"pin\":\"8342\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PIN_LOCKED"));

            // Simular que pasó security.pin.lockout_minutes -> auto-desbloqueo y 200 OK
            ownerJdbc().sql("""
                            UPDATE users
                            SET pin_locked_until = now() - interval '1 second'
                            WHERE id = :uid
                            """)
                    .param("uid", autoUnlockUserId).update();

            mockMvc.perform(post("/api/v1/auth/pin-login")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Terminal-Id", String.valueOf(terminalId))
                            .header("X-Forwarded-For", "10.26.2.3")
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + autoUnlockUser + "\",\"pin\":\"8342\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope").value("POS"));

            Integer resetAttempts = ownerJdbc().sql("SELECT pin_failed_attempts FROM users WHERE id = :uid")
                    .param("uid", autoUnlockUserId).query(Integer.class).single();
            Boolean resetLocked = ownerJdbc().sql("SELECT pin_locked FROM users WHERE id = :uid")
                    .param("uid", autoUnlockUserId).query(Boolean.class).single();
            assertThat(resetAttempts).isZero();
            assertThat(resetLocked).isFalse();
        } finally {
            cleanupSessionAndUsers(
                    List.of(baseUserId, targetUserId, autoUnlockUserId),
                    List.of(terminalId),
                    storeId,
                    null
            );
        }
    }

    @Test
    void pinLoginAppliesRateLimitPerTargetUserAndIp() throws Exception {
        String suffix = uniqueSuffix();
        String baseUser = "base_rl_" + suffix;
        Long baseUserId = insertUser(baseUser, "SecretPass#1", null);
        Long companyId = insertCompany(suffix);
        Long storeId = insertStore(companyId, "R2" + suffix.substring(0, 6).toUpperCase(), "Local RL");
        Long terminalId = insertTerminal(storeId, "TRL-" + suffix.substring(0, 4).toUpperCase());
        assignStore(baseUserId, storeId);

        try {
            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Forwarded-For", "10.26.3.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + baseUser + "\",\"password\":\"SecretPass#1\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            Cookie refreshCookie = loginResult.getResponse().getCookie("pos_refresh");
            String baseToken = jwtTokenService.issueAccessToken(
                    baseUserId, baseUser, TokenScope.FULL, List.of()).accessToken();

            mockMvc.perform(post("/api/v1/auth/session/terminal")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"terminalId\":" + terminalId + "}"))
                    .andExpect(status().isNoContent());

            String target = "ghost_pin_" + suffix;
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/api/v1/auth/pin-login")
                                .header("Authorization", "Bearer " + baseToken)
                                .header("X-Store-Id", String.valueOf(storeId))
                                .header("X-Terminal-Id", String.valueOf(terminalId))
                                .header("X-Forwarded-For", "10.26.3.10")
                                .cookie(refreshCookie)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"" + target + "\",\"pin\":\"4829\"}"))
                        .andExpect(status().isUnauthorized());
            }

            // 6to intento sobre el mismo usuario objetivo -> 429 RATE_LIMITED
            mockMvc.perform(post("/api/v1/auth/pin-login")
                            .header("Authorization", "Bearer " + baseToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .header("X-Terminal-Id", String.valueOf(terminalId))
                            .header("X-Forwarded-For", "10.26.3.11")
                            .cookie(refreshCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + target + "\",\"pin\":\"4829\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        } finally {
            ownerJdbc().sql("DELETE FROM login_attempts WHERE username LIKE :p")
                    .param("p", "%" + suffix + "%").update();
            cleanupSessionAndUsers(List.of(baseUserId), List.of(terminalId), storeId, null);
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

    private void assignStore(Long userId, Long storeId) {
        ownerJdbc().sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:uid, :sid, true)")
                .param("uid", userId).param("sid", storeId).update();
    }

    private Long insertRoleWithPermissions(String roleCode, List<String> permissionCodes) {
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
        return roleId;
    }

    private void assignRole(Long userId, Long roleId) {
        ownerJdbc().sql("INSERT INTO user_roles (user_id, role_id) VALUES (:uid, :rid)")
                .param("uid", userId).param("rid", roleId).update();
    }

    private void cleanupSessionAndUsers(List<Long> userIds, List<Long> terminalIds, Long storeId, Long roleId) {
        // audit_log es append-only (trigger impide DELETE), por lo que users/stores/terminals con sufijo único se conservan.
        for (Long uid : userIds) {
            ownerJdbc().sql("DELETE FROM login_attempts WHERE user_id = :uid").param("uid", uid).update();
            ownerJdbc().sql("DELETE FROM refresh_tokens WHERE user_id = :uid").param("uid", uid).update();
        }
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
