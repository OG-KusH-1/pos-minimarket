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

import java.math.BigDecimal;
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
 * Pruebas de integración para roles, permisos, límites por rol ({@link RoleLimitApi}) con
 * semántica <em>fail-closed</em>, protección del último {@code ADMIN} y auditoría.
 */
class RoleManagementIT extends AbstractIntegrationIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleLimitApi roleLimitApi;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void createRoleSeedsAllLimitsToZeroAndFailClosedAppliesToMissingRows() throws Exception {
        String code = randomRoleCode("ROLE");

        MvcResult createdResult = mockMvc.perform(post("/api/v1/roles")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_ROLE_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "%s",
                                  "name": "Rol Prueba T22",
                                  "description": "Rol temporal"
                                }
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.system").value(false))
                .andReturn();

        long roleId = MAPPER.readTree(createdResult.getResponse().getContentAsString()).get("id").asLong();

        // Verifica que todas las filas de role_limits fueron sembradas en 0
        mockMvc.perform(get("/api/v1/roles/{id}/limits", roleId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_ROLE_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(RoleLimitCode.values().length))
                .andExpect(jsonPath("$[0].value").value(0));

        // Usuario sin roles -> fail-closed: LIMITED(0)
        long userId = insertTestUser("t22_u_" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(roleLimitApi.limitFor(userId, RoleLimitCode.DISCOUNT_ITEM_MAX_PCT))
                .isEqualTo(RoleLimit.zero());
        assertThat(roleLimitApi.limitFor(userId, RoleLimitCode.CASH_OUT_MAX_AMOUNT).allows(BigDecimal.ONE))
                .isFalse();

        // Asignar el rol nuevo (todos en 0) -> sigue siendo LIMITED(0)
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
        assertThat(roleLimitApi.limitFor(userId, RoleLimitCode.DISCOUNT_ITEM_MAX_PCT))
                .isEqualTo(RoleLimit.zero());

        // Borrar manualmente una fila de role_limits -> ausente también equivale a LIMITED(0) (fail-closed)
        jdbc.sql("DELETE FROM role_limits WHERE role_id = :roleId AND limit_code = :code")
                .param("roleId", roleId)
                .param("code", RoleLimitCode.RETURN_MAX_AMOUNT.name())
                .update();
        assertThat(roleLimitApi.limitFor(userId, RoleLimitCode.RETURN_MAX_AMOUNT))
                .isEqualTo(RoleLimit.zero());

        // Actualizar un límite a 15 y otro a null (UNLIMITED) -> códigos omitidos conservan su valor
        mockMvc.perform(put("/api/v1/roles/{id}/limits", roleId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_ROLE_LIMIT_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "limits": [
                                    { "code": "DISCOUNT_ITEM_MAX_PCT", "value": 15 },
                                    { "code": "CASH_OUT_MAX_AMOUNT", "value": null }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        RoleLimit discountLimit = roleLimitApi.limitFor(userId, RoleLimitCode.DISCOUNT_ITEM_MAX_PCT);
        assertThat(discountLimit).isInstanceOf(RoleLimit.Limited.class);
        assertThat(((RoleLimit.Limited) discountLimit).max()).isEqualByComparingTo("15");
        assertThat(discountLimit.allows(new BigDecimal("15"))).isTrue();
        assertThat(discountLimit.allows(new BigDecimal("16"))).isFalse();

        RoleLimit cashOutLimit = roleLimitApi.limitFor(userId, RoleLimitCode.CASH_OUT_MAX_AMOUNT);
        assertThat(cashOutLimit).isEqualTo(RoleLimit.unlimited());
        assertThat(cashOutLimit.allows(new BigDecimal("99999999"))).isTrue();

        // Verificar auditoría ROLE_LIMIT_CHANGED
        Long auditCount = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'ROLE_LIMIT_CHANGED'
                          AND entity_type = 'role'
                          AND entity_id = :entityId
                        """)
                .param("entityId", String.valueOf(roleId))
                .query(Long.class)
                .single();
        assertThat(auditCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void multipleRolesPickMostPermissiveLimit() throws Exception {
        String codeA = randomRoleCode("RA");
        String codeB = randomRoleCode("RB");

        long roleA = createRoleViaApi(codeA);
        long roleB = createRoleViaApi(codeB);

        updateLimitViaApi(roleA, "DISCOUNT_ITEM_MAX_PCT", "10", "RETURN_MAX_AMOUNT", "25000");
        updateLimitViaApi(roleB, "DISCOUNT_ITEM_MAX_PCT", "25", "RETURN_MAX_AMOUNT", null);

        long userId = insertTestUser("t22_multi_" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", userId).param("roleId", roleA).update();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", userId).param("roleId", roleB).update();

        RoleLimit maxPct = roleLimitApi.limitFor(userId, RoleLimitCode.DISCOUNT_ITEM_MAX_PCT);
        assertThat(((RoleLimit.Limited) maxPct).max()).isEqualByComparingTo("25");

        RoleLimit returnMax = roleLimitApi.limitFor(userId, RoleLimitCode.RETURN_MAX_AMOUNT);
        assertThat(returnMax.isUnlimited()).isTrue();
    }

    @Test
    void rolePermissionsUpdateIsAuditedAndProtectsAdminAndSystemAndInUseRoles() throws Exception {
        String code = randomRoleCode("RP");
        long roleId = createRoleViaApi(code);

        // Asignar permisos válidos y verificar auditoría ROLE_PERMISSIONS_CHANGED
        mockMvc.perform(put("/api/v1/roles/{id}/permissions", roleId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_ROLE_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "permissionCodes": ["catalog:product:read", "inventory:stock:read"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionCodes.length()").value(2));

        Long auditCount = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'ROLE_PERMISSIONS_CHANGED'
                          AND entity_type = 'role'
                          AND entity_id = :entityId
                        """)
                .param("entityId", String.valueOf(roleId))
                .query(Long.class)
                .single();
        assertThat(auditCount).isEqualTo(1L);

        // Intentar quitar permisos esenciales al rol ADMIN -> 409 ENTITY_IN_USE
        Long adminRoleId = jdbc.sql("SELECT id FROM roles WHERE code = 'ADMIN'")
                .query(Long.class)
                .single();
        mockMvc.perform(put("/api/v1/roles/{id}/permissions", adminRoleId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_ROLE_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "permissionCodes": ["catalog:product:read"]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

        // Intentar eliminar rol de sistema -> 409 ENTITY_IN_USE
        mockMvc.perform(delete("/api/v1/roles/{id}", adminRoleId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_ROLE_MANAGE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

        // Asignar un usuario al rol custom e intentar eliminarlo -> 409 ENTITY_IN_USE
        long userId = insertTestUser("t22_rdel_" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", userId).param("roleId", roleId).update();

        mockMvc.perform(delete("/api/v1/roles/{id}", roleId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_ROLE_MANAGE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

        // Quitar usuario y eliminar rol -> 204 NO_CONTENT
        jdbc.sql("DELETE FROM user_roles WHERE user_id = :userId AND role_id = :roleId")
                .param("userId", userId).param("roleId", roleId).update();
        mockMvc.perform(delete("/api/v1/roles/{id}", roleId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_ROLE_MANAGE))))
                .andExpect(status().isNoContent());

        // Catálogo de permisos
        mockMvc.perform(get("/api/v1/permissions")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_ROLE_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").isNumber());
    }

    private long createRoleViaApi(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/roles")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_ROLE_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "%s",
                                  "name": "Rol %s"
                                }
                                """.formatted(code, code)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = MAPPER.readTree(result.getResponse().getContentAsString());
        return json.get("id").asLong();
    }

    private void updateLimitViaApi(long roleId, String code1, String val1, String code2, String val2) throws Exception {
        String v1 = val1 == null ? "null" : val1;
        String v2 = val2 == null ? "null" : val2;
        mockMvc.perform(put("/api/v1/roles/{id}/limits", roleId)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.CONFIG_ROLE_LIMIT_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "limits": [
                                    { "code": "%s", "value": %s },
                                    { "code": "%s", "value": %s }
                                  ]
                                }
                                """.formatted(code1, v1, code2, v2)))
                .andExpect(status().isOk());
    }

    private static String randomRoleCode(String prefix) {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        StringBuilder sb = new StringBuilder(prefix).append('_');
        for (int i = 0; i < hex.length(); i++) {
            int val = Character.digit(hex.charAt(i), 16);
            sb.append((char) ('A' + val));
        }
        return sb.toString();
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
}
