package cl.minimarket.pos.security;

import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración para usuarios, anti-escalada (D.4), segregación de funciones,
 * protección del último {@code ADMIN} activo, reset de credenciales y revocación de sesiones
 * auditada ({@code SESSIONS_REVOKED}).
 */
class UserManagementIT extends AbstractIntegrationIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void antiEscalationRestrictsPermissionsAndStoresForManager() throws Exception {
        long storeA = insertTestStore("T22A");
        long storeB = insertTestStore("T22B");
        long managerId = insertTestUser("t22_mgr_" + suffix());
        assignStore(managerId, storeA, true);

        long adminRoleId = findRoleId("ADMIN");
        long cashierRoleId = findRoleId("CASHIER");
        List<String> cashierPerms = findRolePermissions(cashierRoleId);

        // El gerente tiene permisos de gestión de usuarios y los permisos de CASHIER, pero NO all-access ni role:manage
        SimpleGrantedAuthority[] mgrAuthorities = buildAuthorities(
                cashierPerms,
                Permissions.SECURITY_USER_READ,
                Permissions.SECURITY_USER_CREATE,
                Permissions.SECURITY_USER_UPDATE,
                Permissions.SECURITY_USER_DISABLE,
                Permissions.SECURITY_USER_RESET_CREDENTIALS
        );

        String targetUsername = "t22.cajero." + suffix();

        // 1) Intento de asignar rol ADMIN (permisos fuera de su alcance) -> 403 ACCESS_DENIED
        mockMvc.perform(post("/api/v1/users")
                        .with(user(String.valueOf(managerId)).authorities(mgrAuthorities))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "fullName": "Cajero Escalado",
                                  "roleIds": [%d],
                                  "storeIds": [%d],
                                  "defaultStoreId": %d
                                }
                                """.formatted(targetUsername, adminRoleId, storeA, storeA)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 2) Intento de asignar local B que no pertenece al gerente -> 403 ACCESS_DENIED
        mockMvc.perform(post("/api/v1/users")
                        .with(user(String.valueOf(managerId)).authorities(mgrAuthorities))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "fullName": "Cajero Otro Local",
                                  "roleIds": [%d],
                                  "storeIds": [%d],
                                  "defaultStoreId": %d
                                }
                                """.formatted(targetUsername, cashierRoleId, storeB, storeB)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 3) Creación válida con rol subconjunto (CASHIER) y su propio local A -> 201 CREATED
        MvcResult created = mockMvc.perform(post("/api/v1/users")
                        .with(user(String.valueOf(managerId)).authorities(mgrAuthorities))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "fullName": "Cajero Válido",
                                  "email": "%s@minimarket.cl",
                                  "roleIds": [%d],
                                  "storeIds": [%d],
                                  "defaultStoreId": %d
                                }
                                """.formatted(targetUsername, targetUsername, cashierRoleId, storeA, storeA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(targetUsername))
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();

        JsonNode createdJson = MAPPER.readTree(created.getResponse().getContentAsString());
        assertThat(createdJson.get("temporaryPassword").asString()).hasSize(16);
        long targetUserId = createdJson.get("id").asLong();

        // 4) Usuario en local B no es visible ni editable por el gerente
        long outsiderId = insertTestUser("t22_out_" + suffix());
        assignStore(outsiderId, storeB, true);
        assignRole(outsiderId, cashierRoleId);

        mockMvc.perform(get("/api/v1/users/{id}", outsiderId)
                        .with(user(String.valueOf(managerId)).authorities(mgrAuthorities)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/users")
                        .param("q", "t22_out_")
                        .with(user(String.valueOf(managerId)).authorities(mgrAuthorities)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + outsiderId + ")]").isEmpty());

        mockMvc.perform(get("/api/v1/users")
                        .param("q", targetUsername)
                        .with(user(String.valueOf(managerId)).authorities(mgrAuthorities)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + targetUserId + ")]").isNotEmpty());
    }

    @Test
    void userCannotEditOrDisableSelfSegregationOfDuties() throws Exception {
        long storeA = insertTestStore("T22S");
        long selfId = insertTestUser("t22_self_" + suffix());
        long cashierRoleId = findRoleId("CASHIER");
        assignRole(selfId, cashierRoleId);
        assignStore(selfId, storeA, true);

        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[]{
                new SimpleGrantedAuthority(Permissions.SECURITY_USER_UPDATE),
                new SimpleGrantedAuthority(Permissions.SECURITY_USER_DISABLE),
                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS)
        };

        mockMvc.perform(put("/api/v1/users/{id}", selfId)
                        .with(user(String.valueOf(selfId)).authorities(authorities))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "t22_self_edit",
                                  "fullName": "Auto Edición",
                                  "roleIds": [%d],
                                  "storeIds": [%d],
                                  "defaultStoreId": %d,
                                  "version": 0
                                }
                                """.formatted(cashierRoleId, storeA, storeA)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));

        mockMvc.perform(post("/api/v1/users/{id}/disable", selfId)
                        .with(user(String.valueOf(selfId)).authorities(authorities)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));
    }

    @Test
    void cannotDisableOrRemoveRoleFromLastActiveAdmin() throws Exception {
        List<Long> activeAdminIds = jdbc.sql("""
                        SELECT DISTINCT u.id
                        FROM users u
                        JOIN user_roles ur ON ur.user_id = u.id
                        JOIN roles r ON r.id = ur.role_id
                        WHERE u.active = true AND r.code = 'ADMIN'
                        ORDER BY u.id
                        """)
                .query(Long.class)
                .list();
        assertThat(activeAdminIds).isNotEmpty();

        Long lastAdminId = activeAdminIds.getFirst();
        List<Long> extraAdmins = activeAdminIds.subList(1, activeAdminIds.size());
        long cashierRoleId = findRoleId("CASHIER");
        long storeId = insertTestStore("T22L");
        long actorId = insertTestUser("t22_act_" + suffix());

        SimpleGrantedAuthority[] adminAuthorities = new SimpleGrantedAuthority[]{
                new SimpleGrantedAuthority(Permissions.SECURITY_USER_DISABLE),
                new SimpleGrantedAuthority(Permissions.SECURITY_USER_UPDATE),
                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS)
        };

        try {
            if (!extraAdmins.isEmpty()) {
                jdbc.sql("UPDATE users SET active = false WHERE id IN (:ids)")
                        .param("ids", extraAdmins)
                        .update();
            }

            // Desactivar último ADMIN -> 409 ENTITY_IN_USE
            mockMvc.perform(post("/api/v1/users/{id}/disable", lastAdminId)
                            .with(user(String.valueOf(actorId)).authorities(adminAuthorities)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

            // Quitar el rol ADMIN al último ADMIN activo -> 409 ENTITY_IN_USE
            Long version = jdbc.sql("SELECT version FROM users WHERE id = :id")
                    .param("id", lastAdminId)
                    .query(Long.class)
                    .single();
            String username = jdbc.sql("SELECT username FROM users WHERE id = :id")
                    .param("id", lastAdminId)
                    .query(String.class)
                    .single();

            mockMvc.perform(put("/api/v1/users/{id}", lastAdminId)
                            .with(user(String.valueOf(actorId)).authorities(adminAuthorities))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "username": "%s",
                                      "fullName": "Administrador",
                                      "roleIds": [%d],
                                      "storeIds": [%d],
                                      "defaultStoreId": %d,
                                      "version": %d
                                    }
                                    """.formatted(username, cashierRoleId, storeId, storeId, version)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));
        } finally {
            if (!extraAdmins.isEmpty()) {
                jdbc.sql("UPDATE users SET active = true WHERE id IN (:ids)")
                        .param("ids", extraAdmins)
                        .update();
            }
        }
    }

    @Test
    void resetCredentialsUnlockAndSessionRevocationWithAudit() throws Exception {
        long targetId = insertTestUser("t22_ses_" + suffix());
        long actorId = insertTestUser("t22_adm_" + suffix());

        // Dejar usuario con PIN bloqueado y cuenta bloqueada
        Instant future = Instant.now().plus(15, ChronoUnit.MINUTES);
        jdbc.sql("""
                        UPDATE users
                        SET pin_hash = '$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW',
                            pin_failed_attempts = 5,
                            pin_locked = true,
                            pin_locked_until = :future,
                            failed_attempts = 5,
                            locked_until = :future
                        WHERE id = :id
                        """)
                .param("future", Timestamp.from(future))
                .param("id", targetId)
                .update();

        // Insertar 2 sesiones activas en refresh_tokens
        insertActiveRefreshToken(targetId);
        insertActiveRefreshToken(targetId);

        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[]{
                new SimpleGrantedAuthority(Permissions.SECURITY_USER_RESET_CREDENTIALS),
                new SimpleGrantedAuthority(Permissions.SECURITY_SESSION_REVOKE),
                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS)
        };

        // Listar sesiones activas -> 2 sesiones
        mockMvc.perform(get("/api/v1/users/{id}/sessions", targetId)
                        .with(user(String.valueOf(actorId)).authorities(authorities)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Revocar sesiones -> 204 y auditoría SESSIONS_REVOKED
        mockMvc.perform(delete("/api/v1/users/{id}/sessions", targetId)
                        .with(user(String.valueOf(actorId)).authorities(authorities)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}/sessions", targetId)
                        .with(user(String.valueOf(actorId)).authorities(authorities)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        Long revokedAuditCount = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'SESSIONS_REVOKED'
                          AND entity_type = 'user'
                          AND entity_id = :entityId
                        """)
                .param("entityId", String.valueOf(targetId))
                .query(Long.class)
                .single();
        assertThat(revokedAuditCount).isEqualTo(1L);

        // Unlock -> limpia bloqueos de cuenta y PIN
        mockMvc.perform(post("/api/v1/users/{id}/unlock", targetId)
                        .with(user(String.valueOf(actorId)).authorities(authorities)))
                .andExpect(status().isNoContent());

        Boolean pinLocked = jdbc.sql("SELECT pin_locked FROM users WHERE id = :id")
                .param("id", targetId)
                .query(Boolean.class)
                .single();
        assertThat(pinLocked).isFalse();

        // Reset password -> devuelve clave temporal de 16 caracteres
        MvcResult resetResult = mockMvc.perform(post("/api/v1/users/{id}/reset-password", targetId)
                        .with(user(String.valueOf(actorId)).authorities(authorities)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();
        assertThat(MAPPER.readTree(resetResult.getResponse().getContentAsString())
                .get("temporaryPassword").asString()).hasSize(16);

        // Reset PIN -> limpia pin_hash
        mockMvc.perform(post("/api/v1/users/{id}/reset-pin", targetId)
                        .with(user(String.valueOf(actorId)).authorities(authorities)))
                .andExpect(status().isNoContent());

        String pinHash = jdbc.sql("SELECT pin_hash FROM users WHERE id = :id")
                .param("id", targetId)
                .query(String.class)
                .optional()
                .orElse(null);
        assertThat(pinHash).isNull();
    }

    private long insertTestStore(String prefix) {
        String code = (prefix + UUID.randomUUID().toString().substring(0, 5)).toUpperCase().replace("-", "X");
        return jdbc.sql("""
                        INSERT INTO stores (company_id, code, name, address, commune, city, active)
                        SELECT id, :code, :name, 'Av. Test 123', 'Santiago', 'Santiago', true
                        FROM company
                        LIMIT 1
                        RETURNING id
                        """)
                .param("code", code)
                .param("name", "Local " + code)
                .query(Long.class)
                .single();
    }

    private long insertTestUser(String username) {
        return jdbc.sql("""
                        INSERT INTO users (username, full_name, password_hash, must_change_password, active)
                        VALUES (:username, 'Usuario Test', '$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW', false, true)
                        RETURNING id
                        """)
                .param("username", username)
                .query(Long.class)
                .single();
    }

    private void insertActiveRefreshToken(long userId) {
        String tokenHash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        jdbc.sql("""
                        INSERT INTO refresh_tokens (user_id, token_hash, family_id, issued_at, expires_at, absolute_expires_at)
                        VALUES (:userId, :hash, :familyId, :issuedAt, :expiresAt, :absExpiresAt)
                        """)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("familyId", UUID.randomUUID())
                .param("issuedAt", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plus(6, ChronoUnit.HOURS)))
                .param("absExpiresAt", Timestamp.from(now.plus(5, ChronoUnit.DAYS)))
                .update();
    }

    private void assignStore(long userId, long storeId, boolean isDefault) {
        jdbc.sql("""
                        INSERT INTO user_stores (user_id, store_id, is_default)
                        VALUES (:userId, :storeId, :isDefault)
                        """)
                .param("userId", userId)
                .param("storeId", storeId)
                .param("isDefault", isDefault)
                .update();
    }

    private void assignRole(long userId, long roleId) {
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
    }

    private long findRoleId(String code) {
        return jdbc.sql("SELECT id FROM roles WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private List<String> findRolePermissions(long roleId) {
        return jdbc.sql("""
                        SELECT p.code
                        FROM permissions p
                        JOIN role_permissions rp ON rp.permission_id = p.id
                        WHERE rp.role_id = :roleId
                        """)
                .param("roleId", roleId)
                .query(String.class)
                .list();
    }

    private static SimpleGrantedAuthority[] buildAuthorities(List<String> base, String... extra) {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(base);
        all.addAll(List.of(extra));
        return all.stream().map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
