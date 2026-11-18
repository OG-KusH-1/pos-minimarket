package cl.minimarket.pos.security;

import cl.minimarket.pos.security.auth.CurrentUser;
import cl.minimarket.pos.security.auth.CurrentUserService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del flujo núcleo de autenticación: login, {@code /auth/me}, cambio de
 * contraseña y evaluación de permisos con local en {@link CurrentUserService}.
 */
class SecurityAuthIT extends AbstractIntegrationIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CurrentUserService currentUserService;

    @Test
    void adminSeedLoginSucceedsWithMustChangePasswordTrue() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"DevAdmin#2026"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.scope").value("FULL"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"));
    }

    @Test
    void loginWithWrongPasswordReturns401InvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"WrongPassword1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void lockedUserReturns422AccountLockedAndInactiveReturns401() throws Exception {
        String suffix = uniqueSuffix();
        String lockedUser = "locked_" + suffix;
        String inactiveUser = "inactive_" + suffix;
        String hash = passwordEncoder.encode("Secret1234");

        Long lockedId = ownerJdbc().sql("""
                        INSERT INTO users (username, full_name, password_hash, must_change_password, active, locked_until)
                        VALUES (:u, 'Usuario Bloqueado', :h, false, true, :lockedUntil)
                        RETURNING id
                        """)
                .param("u", lockedUser)
                .param("h", hash)
                .param("lockedUntil", java.sql.Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)))
                .query(Long.class)
                .single();

        Long inactiveId = ownerJdbc().sql("""
                        INSERT INTO users (username, full_name, password_hash, must_change_password, active)
                        VALUES (:u, 'Usuario Inactivo', :h, false, false)
                        RETURNING id
                        """)
                .param("u", inactiveUser)
                .param("h", hash)
                .query(Long.class)
                .single();

        try {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + lockedUser + "\",\"password\":\"WrongPassword1\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + lockedUser + "\",\"password\":\"Secret1234\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + inactiveUser + "\",\"password\":\"Secret1234\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"no_existe_" + suffix + "\",\"password\":\"Secret1234\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        } finally {
            ownerJdbc().sql("DELETE FROM users WHERE id IN (:id1, :id2)")
                    .param("id1", lockedId)
                    .param("id2", inactiveId)
                    .update();
        }
    }

    @Test
    void meAndChangePasswordFlowUpdatesMustChangePassword() throws Exception {
        String username = "u_" + uniqueSuffix();
        String initialPass = "TempPass#2026";
        String newPass = "NewValidPass#2026";
        String hash = passwordEncoder.encode(initialPass);

        Long userId = ownerJdbc().sql("""
                        INSERT INTO users (username, full_name, password_hash, must_change_password, active)
                        VALUES (:u, 'Usuario Prueba', :h, true, true)
                        RETURNING id
                        """)
                .param("u", username)
                .param("h", hash)
                .query(Long.class)
                .single();

        ownerJdbc().sql("""
                        INSERT INTO user_roles (user_id, role_id)
                        SELECT :uid, id FROM roles WHERE code = 'ADMIN'
                        """)
                .param("uid", userId)
                .update();

        try {
            String token = loginAndExtractToken(username, initialPass, true);

            // GET /auth/me es global: no requiere X-Store-Id
            mockMvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(userId))
                    .andExpect(jsonPath("$.username").value(username))
                    .andExpect(jsonPath("$.scope").value("FULL"));

            // Política de contraseña inválida -> 422 PASSWORD_POLICY
            mockMvc.perform(post("/api/v1/auth/me/password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"" + initialPass + "\",\"newPassword\":\"short1\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PASSWORD_POLICY"));

            // Contraseña actual errónea -> 401 INVALID_CREDENTIALS
            mockMvc.perform(post("/api/v1/auth/me/password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"WrongCurrent1\",\"newPassword\":\"" + newPass + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

            // Cambio válido -> 204 y mustChangePassword pasa a false
            mockMvc.perform(post("/api/v1/auth/me/password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"" + initialPass + "\",\"newPassword\":\"" + newPass + "\"}"))
                    .andExpect(status().isNoContent());

            loginAndExtractToken(username, newPass, false);
        } finally {
            ownerJdbc().sql("DELETE FROM users WHERE id = :id").param("id", userId).update();
        }
    }

    @Test
    void hasPermissionRespectsStoreAssignmentAndAllAccess() {
        String suffix = uniqueSuffix();
        String username = "store_user_" + suffix;
        Long userId = ownerJdbc().sql("""
                        INSERT INTO users (username, full_name, password_hash, must_change_password, active)
                        VALUES (:u, 'Usuario Local', :h, false, true)
                        RETURNING id
                        """)
                .param("u", username)
                .param("h", passwordEncoder.encode("Pass123456"))
                .query(Long.class)
                .single();

        Long companyId = ownerJdbc().sql("SELECT id FROM company LIMIT 1").query(Long.class).single();
        Long store1 = ownerJdbc().sql("""
                        INSERT INTO stores (company_id, code, name, address, commune, city, active)
                        VALUES (:cid, :code, 'Local 1', 'Dir 1', 'Santiago', 'Santiago', true)
                        RETURNING id
                        """)
                .param("cid", companyId)
                .param("code", "S1" + suffix.substring(0, 6).toUpperCase())
                .query(Long.class)
                .single();
        Long store2 = ownerJdbc().sql("""
                        INSERT INTO stores (company_id, code, name, address, commune, city, active)
                        VALUES (:cid, :code, 'Local 2', 'Dir 2', 'Santiago', 'Santiago', true)
                        RETURNING id
                        """)
                .param("cid", companyId)
                .param("code", "S2" + suffix.substring(0, 6).toUpperCase())
                .query(Long.class)
                .single();

        ownerJdbc().sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:uid, :sid, true)")
                .param("uid", userId)
                .param("sid", store1)
                .update();

        try {
            CurrentUser restricted = new CurrentUser(
                    userId, username, TokenScope.FULL, Set.of(Permissions.INVENTORY_STOCK_READ));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(restricted, null,
                            List.of(new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ))));

            assertThat(currentUserService.hasPermission(Permissions.INVENTORY_STOCK_READ, store1)).isTrue();
            assertThat(currentUserService.hasPermission(Permissions.INVENTORY_STOCK_READ, store2)).isFalse();
            assertThat(currentUserService.hasPermission(Permissions.CATALOG_PRODUCT_CREATE, store1)).isFalse();

            CurrentUser allAccess = new CurrentUser(
                    userId, username, TokenScope.FULL,
                    Set.of(Permissions.INVENTORY_STOCK_READ, Permissions.SECURITY_STORE_ALL_ACCESS));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(allAccess, null,
                            List.of(
                                    new SimpleGrantedAuthority(Permissions.INVENTORY_STOCK_READ),
                                    new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS))));

            assertThat(currentUserService.hasPermission(Permissions.INVENTORY_STOCK_READ, store2)).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
            ownerJdbc().sql("DELETE FROM user_stores WHERE user_id = :uid").param("uid", userId).update();
            ownerJdbc().sql("DELETE FROM stores WHERE id IN (:s1, :s2)")
                    .param("s1", store1)
                    .param("s2", store2)
                    .update();
            ownerJdbc().sql("DELETE FROM users WHERE id = :id").param("id", userId).update();
        }
    }

    private String loginAndExtractToken(String username, String password, boolean expectedMustChange) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(expectedMustChange))
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        return body.path("accessToken").asString();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
