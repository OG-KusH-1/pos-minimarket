package cl.minimarket.pos.security;

import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.security.auth.refresh.RefreshTokenService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de T21:
 * <ul>
 *   <li>{@code GET /auth/me/stores} devuelve solo los locales asignados (o todos con {@code all-access}).</li>
 *   <li>{@code StoreAccessValidator} real: {@code X-Store-Id} ajeno responde 403 {@code STORE_ACCESS_DENIED};
 *       terminal ajena o inactiva responde 422 {@code TERMINAL_INVALID}.</li>
 *   <li>Refresh con rotación conserva {@code terminal_id} y la reutilización revoca toda la familia.</li>
 *   <li>{@code POST /auth/logout} revoca la familia y borra la cookie {@code pos_refresh}.</li>
 *   <li>{@code POST /auth/session/terminal} persiste {@code refresh_tokens.terminal_id} y lo conserva al rotar.</li>
 *   <li>Rate limit Bucket4j: el intento 11 por minuto desde una IP responde 429 {@code RATE_LIMITED}.</li>
 *   <li>Bloqueo por intentos: 5 fallos bloquean la cuenta con 422 {@code ACCOUNT_LOCKED} y registran {@code login_attempts}.</li>
 * </ul>
 */
class AuthSessionAndAccessIT extends AbstractIntegrationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void myStoresReturnsOnlyAssignedAndForeignStoreHeaderReturns403() throws Exception {
        String suffix = uniqueSuffix();
        String username = "stores_u_" + suffix;
        Long userId = insertUser(username, "Pass123456");
        Long companyId = insertCompany(suffix);
        Long assignedStore = insertStore(companyId, "A1" + suffix.substring(0, 6).toUpperCase(), "Local Asignado");
        Long foreignStore = insertStore(companyId, "F1" + suffix.substring(0, 6).toUpperCase(), "Local Ajeno");
        Long foreignTerminal = insertTerminal(foreignStore, "TF-" + suffix.substring(0, 6).toUpperCase());
        Long assignedTerminal = insertTerminal(assignedStore, "TA-" + suffix.substring(0, 6).toUpperCase());

        ownerJdbc().sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:uid, :sid, true)")
                .param("uid", userId)
                .param("sid", assignedStore)
                .update();

        try {
            String token = jwtTokenService.issueAccessToken(
                    userId, username, TokenScope.FULL, List.of()).accessToken();

            // GET /auth/me/stores devuelve solo el local asignado
            mockMvc.perform(get("/api/v1/auth/me/stores")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(assignedStore))
                    .andExpect(jsonPath("$[0].name").value("Local Asignado"));

            // X-Store-Id ajeno -> 403 STORE_ACCESS_DENIED (validado por StoreAccessValidatorImpl real)
            mockMvc.perform(post("/api/v1/auth/session/terminal")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Store-Id", String.valueOf(foreignStore))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"terminalId\":" + foreignTerminal + "}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));

            // X-Store-Id asignado con terminal de otro local -> 422 TERMINAL_INVALID
            mockMvc.perform(post("/api/v1/auth/session/terminal")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Store-Id", String.valueOf(assignedStore))
                            .header("X-Terminal-Id", String.valueOf(foreignTerminal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"terminalId\":" + assignedTerminal + "}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("TERMINAL_INVALID"));
        } finally {
            ownerJdbc().sql("DELETE FROM user_stores WHERE user_id = :uid").param("uid", userId).update();
            ownerJdbc().sql("DELETE FROM terminals WHERE id IN (:t1, :t2)")
                    .param("t1", assignedTerminal).param("t2", foreignTerminal).update();
            ownerJdbc().sql("DELETE FROM stores WHERE id IN (:s1, :s2)")
                    .param("s1", assignedStore).param("s2", foreignStore).update();
            ownerJdbc().sql("DELETE FROM users WHERE id = :uid").param("uid", userId).update();
        }
    }

    @Test
    void sessionTerminalPersistsTerminalIdAndRefreshPreservesItWhileReuseRevokesFamily() throws Exception {
        String suffix = uniqueSuffix();
        String username = "rt_u_" + suffix;
        String password = "SecretPass#1";
        Long userId = insertUser(username, password);
        Long companyId = insertCompany(suffix);
        Long storeId = insertStore(companyId, "R1" + suffix.substring(0, 6).toUpperCase(), "Local Refresh");
        Long terminalId = insertTerminal(storeId, "TR-" + suffix.substring(0, 6).toUpperCase());

        ownerJdbc().sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:uid, :sid, true)")
                .param("uid", userId)
                .param("sid", storeId)
                .update();

        try {
            // 1. Login entrega cookie pos_refresh HttpOnly
            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr("198.51.100.10");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Set-Cookie"))
                    .andReturn();

            Cookie initialCookie = loginResult.getResponse().getCookie("pos_refresh");
            assertThat(initialCookie).isNotNull();
            assertThat(initialCookie.isHttpOnly()).isTrue();
            String initialTokenHash = RefreshTokenService.sha256Hex(initialCookie.getValue());

            String accessToken = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(loginResult.getResponse().getContentAsString())
                    .path("accessToken").asString();

            // 2. POST /auth/session/terminal liga el refresh token vigente a la terminal
            mockMvc.perform(post("/api/v1/auth/session/terminal")
                            .header("Authorization", "Bearer " + accessToken)
                            .header("X-Store-Id", String.valueOf(storeId))
                            .cookie(initialCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"terminalId\":" + terminalId + "}"))
                    .andExpect(status().isNoContent());

            Long persistedTerminal = ownerJdbc().sql("SELECT terminal_id FROM refresh_tokens WHERE token_hash = :h")
                    .param("h", initialTokenHash)
                    .query(Long.class)
                    .single();
            assertThat(persistedTerminal).isEqualTo(terminalId);

            // 3. POST /auth/refresh rota el token y conserva terminal_id
            MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                            .cookie(initialCookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andReturn();

            Cookie rotatedCookie = refreshResult.getResponse().getCookie("pos_refresh");
            assertThat(rotatedCookie).isNotNull();
            assertThat(rotatedCookie.getValue()).isNotEqualTo(initialCookie.getValue());

            String rotatedHash = RefreshTokenService.sha256Hex(rotatedCookie.getValue());
            Long rotatedTerminal = ownerJdbc().sql("SELECT terminal_id FROM refresh_tokens WHERE token_hash = :h")
                    .param("h", rotatedHash)
                    .query(Long.class)
                    .single();
            assertThat(rotatedTerminal).isEqualTo(terminalId);

            // 4. Reutilizar el token inicial revoca toda la familia
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .cookie(initialCookie))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

            // El token rotado de la misma familia ahora también debe estar revocado
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .cookie(rotatedCookie))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        } finally {
            ownerJdbc().sql("DELETE FROM refresh_tokens WHERE user_id = :uid").param("uid", userId).update();
            ownerJdbc().sql("DELETE FROM login_attempts WHERE user_id = :uid").param("uid", userId).update();
            ownerJdbc().sql("DELETE FROM user_stores WHERE user_id = :uid").param("uid", userId).update();
            ownerJdbc().sql("DELETE FROM terminals WHERE id = :tid").param("tid", terminalId).update();
            ownerJdbc().sql("DELETE FROM stores WHERE id = :sid").param("sid", storeId).update();
            ownerJdbc().sql("DELETE FROM users WHERE id = :uid").param("uid", userId).update();
        }
    }

    @Test
    void logoutRevokesFamilyAndClearsCookie() throws Exception {
        String suffix = uniqueSuffix();
        String username = "logout_u_" + suffix;
        String password = "SecretPass#1";
        Long userId = insertUser(username, password);

        try {
            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr("198.51.100.20");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();

            Cookie refreshCookie = loginResult.getResponse().getCookie("pos_refresh");
            assertThat(refreshCookie).isNotNull();
            String accessToken = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(loginResult.getResponse().getContentAsString())
                    .path("accessToken").asString();

            MvcResult logoutResult = mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken)
                            .cookie(refreshCookie))
                    .andExpect(status().isNoContent())
                    .andReturn();

            Cookie clearedCookie = logoutResult.getResponse().getCookie("pos_refresh");
            assertThat(clearedCookie).isNotNull();
            assertThat(clearedCookie.getMaxAge()).isZero();

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .cookie(refreshCookie))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        } finally {
            ownerJdbc().sql("DELETE FROM refresh_tokens WHERE user_id = :uid").param("uid", userId).update();
            ownerJdbc().sql("DELETE FROM login_attempts WHERE user_id = :uid").param("uid", userId).update();
            ownerJdbc().sql("DELETE FROM users WHERE id = :uid").param("uid", userId).update();
        }
    }

    @Test
    void eleventhLoginAttemptFromSameIpReturns429RateLimited() throws Exception {
        String ip = "198.51.100." + (100 + Math.abs(uniqueSuffix().hashCode() % 100));
        try {
            // Cada intento apunta a un usuario distinto para probar específicamente el límite de 10/min por IP
            for (int i = 1; i <= 10; i++) {
                mockMvc.perform(post("/api/v1/auth/login")
                                .with(req -> {
                                    req.setRemoteAddr(ip);
                                    return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"rl_" + i + "_" + uniqueSuffix() + "\",\"password\":\"WrongPass1\"}"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            }

            // Intento 11 desde la misma IP -> 429 RATE_LIMITED
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"rl_11_" + uniqueSuffix() + "\",\"password\":\"WrongPass1\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        } finally {
            ownerJdbc().sql("DELETE FROM login_attempts WHERE ip = CAST(:ip AS inet)")
                    .param("ip", ip)
                    .update();
        }
    }

    @Test
    void fiveFailedPasswordAttemptsLockAccountWith422AccountLocked() throws Exception {
        String suffix = uniqueSuffix();
        String username = "lock_u_" + suffix;
        String ip = "198.51.100." + (1 + Math.abs(suffix.hashCode() % 90));
        Long userId = insertUser(username, "ValidPass#1");

        try {
            for (int i = 1; i <= 4; i++) {
                mockMvc.perform(post("/api/v1/auth/login")
                                .with(req -> {
                                    req.setRemoteAddr(ip);
                                    return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"" + username + "\",\"password\":\"WrongPass1\"}"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            }

            // 5º fallo -> bloquea cuenta y responde 422 ACCOUNT_LOCKED
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"WrongPass1\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

            Integer attemptsRecorded = ownerJdbc().sql("""
                            SELECT COUNT(*)
                            FROM login_attempts
                            WHERE user_id = :uid AND success = false AND failure_reason = 'BAD_CREDENTIALS'
                            """)
                    .param("uid", userId)
                    .query(Integer.class)
                    .single();
            assertThat(attemptsRecorded).isEqualTo(5);

            // Desde otra IP (para no chocar con rate limit de usuario, probamos con otro IP cuando el rate limit por usuario es 5):
            // Verificamos en BD que locked_until quedó en el futuro y failed_attempts = 5
            Integer failedAttempts = ownerJdbc().sql("SELECT failed_attempts FROM users WHERE id = :uid")
                    .param("uid", userId).query(Integer.class).single();
            java.sql.Timestamp lockedUntil = ownerJdbc().sql("SELECT locked_until FROM users WHERE id = :uid")
                    .param("uid", userId).query(java.sql.Timestamp.class).single();
            assertThat(failedAttempts).isEqualTo(5);
            assertThat(lockedUntil).isNotNull();
            assertThat(lockedUntil.toInstant()).isAfter(java.time.Instant.now());
        } finally {
            ownerJdbc().sql("DELETE FROM login_attempts WHERE user_id = :uid").param("uid", userId).update();
            ownerJdbc().sql("DELETE FROM users WHERE id = :uid").param("uid", userId).update();
        }
    }

    private Long insertUser(String username, String rawPassword) {
        return ownerJdbc().sql("""
                        INSERT INTO users (username, full_name, password_hash, must_change_password, active)
                        VALUES (:u, :name, :h, false, true)
                        RETURNING id
                        """)
                .param("u", username)
                .param("name", "Usuario " + username)
                .param("h", passwordEncoder.encode(rawPassword))
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

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
