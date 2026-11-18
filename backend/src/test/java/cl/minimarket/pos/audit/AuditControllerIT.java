package cl.minimarket.pos.audit;

import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración HTTP para {@code /api/v1/audit-log}.
 */
class AuditControllerIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuditService auditService;

    @Autowired
    TransactionTemplate txTemplate;

    @Test
    void listFiltersByActionEntityAndUser() throws Exception {
        String marker = "api-" + UUID.randomUUID().toString().substring(0, 12);
        long firstId = recordSampleEvent("IT_FILTER_A", "product", marker + "-1", 1L);
        recordSampleEvent("IT_FILTER_B", "sale", marker + "-2", 2L);
        String expectedUsername = appJdbc().sql("SELECT username FROM users WHERE id = 1")
                .query(String.class)
                .single();

        mvc.perform(asAuditor(get("/api/v1/audit-log")
                        .param("action", "IT_FILTER_A")
                        .param("entityType", "product")
                        .param("entityId", marker + "-1")
                        .param("userId", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(firstId))
                .andExpect(jsonPath("$.content[0].action").value("IT_FILTER_A"))
                .andExpect(jsonPath("$.content[0].entityType").value("product"))
                .andExpect(jsonPath("$.content[0].entityId").value(marker + "-1"))
                .andExpect(jsonPath("$.content[0].username").value(expectedUsername))
                .andExpect(jsonPath("$.content[0].newValue.marker").value(marker + "-1"));

        mvc.perform(asAuditor(get("/api/v1/audit-log/" + firstId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.action").value("IT_FILTER_A"));
    }

    @Test
    void verifyEndpointReturnsValidAndDetectsBrokenChain() throws Exception {
        String marker = "ver-" + UUID.randomUUID().toString().substring(0, 12);
        long firstId = recordSampleEvent("IT_VERIFY_1", "product", marker + "-1", 1L);
        long secondId = recordSampleEvent("IT_VERIFY_2", "product", marker + "-2", 1L);

        String body = """
                {"fromId": %d, "toId": %d}
                """.formatted(firstId, secondId);

        mvc.perform(asVerifier(post("/api/v1/audit-log/verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.checked", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.firstBrokenId").doesNotExist());

        String originalReason = ownerJdbc().sql("SELECT reason FROM audit_log WHERE id = :id")
                .param("id", secondId)
                .query(String.class)
                .single();

        // Alteramos nuestra propia fila como pos_owner y la restauramos antes de rehabilitar el trigger.
        ownerJdbc().sql("ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable").update();
        try {
            ownerJdbc().sql("UPDATE audit_log SET reason = 'manipulado-it' WHERE id = :id")
                    .param("id", secondId)
                    .update();

            mvc.perform(asVerifier(post("/api/v1/audit-log/verify"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.firstBrokenId").value(secondId));
        } finally {
            try {
                ownerJdbc().sql("UPDATE audit_log SET reason = :reason WHERE id = :id")
                        .param("reason", originalReason)
                        .param("id", secondId)
                        .update();
            } finally {
                ownerJdbc().sql("ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable").update();
            }
        }
    }

    @Test
    void endpointsRejectWithoutRequiredPermission() throws Exception {
        mvc.perform(get("/api/v1/audit-log").with(user("1")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/audit-log/1").with(user("1")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/audit-log/verify")
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_AUDIT_READ))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/audit-log")
                        .with(user("non-numeric").authorities(new SimpleGrantedAuthority(Permissions.SECURITY_AUDIT_READ))))
                .andExpect(status().isForbidden());
    }

    private long recordSampleEvent(String action, String entityType, String entityId, long storeId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "n/a",
                        List.of(new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS))));
        try (var ignored = StoreContext.bind(new StoreContext(storeId, null))) {
            txTemplate.executeWithoutResult(status -> auditService.record(new AuditEvent(
                    action,
                    entityType,
                    entityId,
                    null,
                    Map.of("marker", entityId),
                    "prueba IT",
                    null
            )));
            return appJdbc().sql("SELECT id FROM audit_log WHERE entity_id = :entityId ORDER BY id DESC LIMIT 1")
                    .param("entityId", entityId)
                    .query(Long.class)
                    .single();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static MockHttpServletRequestBuilder asAuditor(MockHttpServletRequestBuilder request) {
        return request.with(user("1").authorities(
                new SimpleGrantedAuthority(Permissions.SECURITY_AUDIT_READ),
                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS)
        ));
    }

    private static MockHttpServletRequestBuilder asVerifier(MockHttpServletRequestBuilder request) {
        return request.with(user("1").authorities(
                new SimpleGrantedAuthority(Permissions.SECURITY_AUDIT_VERIFY),
                new SimpleGrantedAuthority(Permissions.SECURITY_STORE_ALL_ACCESS)
        ));
    }
}
