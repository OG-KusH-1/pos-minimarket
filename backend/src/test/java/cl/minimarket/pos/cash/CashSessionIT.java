package cl.minimarket.pos.cash;

import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del módulo {@code cash} (T29):
 * <ul>
 *   <li>Apertura, consulta {@code /current} (204/200), idempotencia y RN-10 ({@code CASH_SESSION_ALREADY_OPEN}).</li>
 *   <li>Movimientos {@code CASH_IN} / {@code CASH_OUT}, RN-24 ({@code CASH_OUT_EXCEEDS_AVAILABLE} y override {@code CASH_OUT_OVER_LIMIT}).</li>
 *   <li>Cierre con arqueo, tolerancia, {@code requiresDifferenceApproval}, override de cierre y SOD RN-23 en {@code /approve-difference}.</li>
 *   <li>Alcance P en historial, comprobante JSON y PDF ({@code cash-closing.html}).</li>
 *   <li>{@code StoreUsageGuard} y {@code TerminalUsageGuard} (409 {@code ENTITY_IN_USE} con turno abierto).</li>
 * </ul>
 */
class CashSessionIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void openCurrentRn10AndUsageGuardsWorkEndToEnd() throws Exception {
        String sfx = uniqueSuffix();
        Long storeId = insertStore("CS" + sfx.substring(0, 6).toUpperCase(), "Local Caja " + sfx);
        Long terminal1 = insertTerminal(storeId, "TC1-" + sfx.substring(0, 4).toUpperCase());
        Long terminal2 = insertTerminal(storeId, "TC2-" + sfx.substring(0, 4).toUpperCase());

        Long cashier1 = insertUser("caj1_" + sfx, "Pass#1234", null);
        Long cashier2 = insertUser("caj2_" + sfx, "Pass#1234", null);
        Long adminId = insertUser("adm_" + sfx, "Pass#1234", null);

        assignStore(cashier1, storeId, true);
        assignStore(cashier2, storeId, true);
        assignStore(adminId, storeId, true);

        Long cashierRole = insertRoleWithLimits(
                "CASH_CAJ_" + sfx,
                List.of("cash:session:open", "cash:session:close", "cash:session:read",
                        "cash:movement:cash-in", "cash:movement:cash-out"),
                Map.of("CASH_OUT_MAX_AMOUNT", new BigDecimal("100000"))
        );
        Long adminRole = insertRoleWithLimits(
                "CASH_ADM_" + sfx,
                List.of("config:store:manage", "config:terminal:manage", "cash:session:read",
                        "cash:session:close-others", "cash:difference:approve"),
                Map.of()
        );
        assignRole(cashier1, cashierRole);
        assignRole(cashier2, cashierRole);
        assignRole(adminId, adminRole);

        String caj1Token = tokenFor(cashier1, "caj1_" + sfx, cashierRole);
        String caj2Token = tokenFor(cashier2, "caj2_" + sfx, cashierRole);
        String admToken = tokenFor(adminId, "adm_" + sfx, adminRole);

        // Sin turno abierto -> 204
        mvc.perform(get("/api/v1/cash-sessions/current")
                        .header("Authorization", "Bearer " + caj1Token)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminal1))
                .andExpect(status().isNoContent());

        // Abrir turno -> 201 + idempotencia
        UUID openKey = UUID.randomUUID();
        MvcResult opened = mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + caj1Token)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminal1)
                        .header("Idempotency-Key", openKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 50000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openingAmount").value(50000))
                .andExpect(jsonPath("$.terminal.id").value(terminal1))
                .andExpect(jsonPath("$.cashier.id").value(cashier1))
                .andReturn();

        long sessionId = JSON.readTree(opened.getResponse().getContentAsString()).get("id").asLong();

        // Reintento idempotente devuelve el mismo turno
        mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + caj1Token)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminal1)
                        .header("Idempotency-Key", openKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 50000}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(sessionId));

        // /current devuelve el turno abierto
        mvc.perform(get("/api/v1/cash-sessions/current")
                        .header("Authorization", "Bearer " + caj1Token)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminal1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId));

        // RN-10: otro cajero en la misma terminal -> 422 CASH_SESSION_ALREADY_OPEN
        mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + caj2Token)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminal1)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 30000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_ALREADY_OPEN"));

        // RN-10: mismo cajero en otra terminal -> 422 CASH_SESSION_ALREADY_OPEN
        mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + caj1Token)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminal2)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 30000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_ALREADY_OPEN"));

        // StoreUsageGuard y TerminalUsageGuard: con turno abierto -> 409 ENTITY_IN_USE
        mvc.perform(post("/api/v1/terminals/" + terminal1 + "/deactivate")
                        .header("Authorization", "Bearer " + admToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

        mvc.perform(post("/api/v1/stores/" + storeId + "/deactivate")
                        .header("Authorization", "Bearer " + admToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

        // Cerrar turno dentro de tolerancia
        mvc.perform(post("/api/v1/cash-sessions/" + sessionId + "/close")
                        .header("Authorization", "Bearer " + caj1Token)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "counts": [
                                    {"paymentMethod": "CASH", "countedAmount": 50000, "denominationDetail": {"10000": 5}}
                                  ],
                                  "notes": "Cuadre exacto"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.requiresDifferenceApproval").value(false))
                .andExpect(jsonPath("$.differenceTotal").value(0));

        // Sin turnos abiertos ya permite desactivar terminal y local
        mvc.perform(post("/api/v1/terminals/" + terminal1 + "/deactivate")
                        .header("Authorization", "Bearer " + admToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/stores/" + storeId + "/deactivate")
                        .header("Authorization", "Bearer " + admToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void movementsEnforceAvailableCashLimitAndOverrideRn24() throws Exception {
        String sfx = uniqueSuffix();
        Long storeId = insertStore("MV" + sfx.substring(0, 6).toUpperCase(), "Local Mov " + sfx);
        Long terminalId = insertTerminal(storeId, "TM-" + sfx.substring(0, 5).toUpperCase());

        Long cashierId = insertUser("mvcaj_" + sfx, "Pass#1234", null);
        Long supId = insertUser("mvsup_" + sfx, "SupPass#1", "7418");
        assignStore(cashierId, storeId, true);
        assignStore(supId, storeId, true);

        Long cashierRole = insertRoleWithLimits(
                "MV_CAJ_" + sfx,
                List.of("cash:session:open", "cash:session:read",
                        "cash:movement:cash-in", "cash:movement:cash-out"),
                Map.of("CASH_OUT_MAX_AMOUNT", new BigDecimal("100000"))
        );
        Long supRole = insertRoleWithLimits(
                "MV_SUP_" + sfx,
                List.of("cash:movement:override"),
                Map.of("CASH_OUT_MAX_AMOUNT", new BigDecimal("300000"))
        );
        assignRole(cashierId, cashierRole);
        assignRole(supId, supRole);

        String cajToken = tokenFor(cashierId, "mvcaj_" + sfx, cashierRole);

        // Abrir con $50.000 + ingreso de $150.000 = $200.000 disponibles
        MvcResult opened = mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 50000}"))
                .andExpect(status().isCreated())
                .andReturn();
        long sessionId = JSON.readTree(opened.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/cash-sessions/" + sessionId + "/movements")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movementType\": \"CASH_IN\", \"amount\": 150000, \"reason\": \"Sencillo de caja\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movementType").value("CASH_IN"))
                .andExpect(jsonPath("$.amount").value(150000));

        // RN-24: retiro mayor que el efectivo disponible ($250.000 > $200.000) -> 422 CASH_OUT_EXCEEDS_AVAILABLE
        mvc.perform(post("/api/v1/cash-sessions/" + sessionId + "/movements")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movementType\": \"CASH_OUT\", \"amount\": 250000, \"reason\": \"Excede caja\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CASH_OUT_EXCEEDS_AVAILABLE"));

        // RN-24: retiro sobre el límite del rol ($150.000 > $100.000) -> 422 OVERRIDE_REQUIRED
        UUID outKey = UUID.randomUUID();
        MvcResult requiredRes = mvc.perform(post("/api/v1/cash-sessions/" + sessionId + "/movements")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", outKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movementType\": \"CASH_OUT\", \"amount\": 150000, \"reason\": \"Retiro a bóveda\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.action").value("CASH_OUT_OVER_LIMIT"))
                .andReturn();

        JsonNode contextNode = JSON.readTree(requiredRes.getResponse().getContentAsString()).get("context");
        assertThat(contextNode.has("cashSessionId")).isTrue();
        assertThat(contextNode.has("sessionId")).isFalse();

        // Solicitar override de supervisor con PIN
        MvcResult ovRes = mvc.perform(post("/api/v1/auth/overrides")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "action", "CASH_OUT_OVER_LIMIT",
                                "context", contextNode,
                                "supervisorUsername", "mvsup_" + sfx,
                                "method", "PIN",
                                "secret", "7418",
                                "reason", "Autorizo retiro"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String overrideToken = JSON.readTree(ovRes.getResponse().getContentAsString()).get("overrideToken").asText();

        // Reintento con la misma Idempotency-Key + X-Override-Token -> 201 Created
        mvc.perform(post("/api/v1/cash-sessions/" + sessionId + "/movements")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", outKey.toString())
                        .header("X-Override-Token", overrideToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movementType\": \"CASH_OUT\", \"amount\": 150000, \"reason\": \"Retiro a bóveda\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorizationId").isNumber());

        // Verificar summary: 50.000 + 150.000 - 150.000 = 50.000
        mvc.perform(get("/api/v1/cash-sessions/" + sessionId + "/summary")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashIn").value(150000))
                .andExpect(jsonPath("$.cashOut").value(150000))
                .andExpect(jsonPath("$.expectedCash").value(50000));
    }

    @Test
    void closeDifferenceApprovalSodPdfAndScopePWorkCorrectly() throws Exception {
        String sfx = uniqueSuffix();
        Long storeId = insertStore("CL" + sfx.substring(0, 6).toUpperCase(), "Local Cierre " + sfx);
        Long terminalId = insertTerminal(storeId, "TCL-" + sfx.substring(0, 4).toUpperCase());

        Long cashierId = insertUser("clcaj_" + sfx, "Pass#1234", null);
        Long otherCashierId = insertUser("clot_" + sfx, "Pass#1234", null);
        Long supId = insertUser("clsup_" + sfx, "SupPass#1", "8529");
        assignStore(cashierId, storeId, true);
        assignStore(otherCashierId, storeId, true);
        assignStore(supId, storeId, true);

        Long cashierWithApproveRole = insertRoleWithLimits(
                "CL_CAJ_" + sfx,
                List.of("cash:session:open", "cash:session:close", "cash:session:read", "cash:difference:approve"),
                Map.of()
        );
        Long plainCashierRole = insertRoleWithLimits(
                "CL_PLAIN_" + sfx,
                List.of("cash:session:read"),
                Map.of()
        );
        Long supRole = insertRoleWithLimits(
                "CL_SUP_" + sfx,
                List.of("cash:session:read", "cash:session:close-others", "cash:difference:approve"),
                Map.of()
        );
        assignRole(cashierId, cashierWithApproveRole);
        assignRole(otherCashierId, plainCashierRole);
        assignRole(supId, supRole);

        String cajToken = tokenFor(cashierId, "clcaj_" + sfx, cashierWithApproveRole);
        String otherCajToken = tokenFor(otherCashierId, "clot_" + sfx, plainCashierRole);
        String supToken = tokenFor(supId, "clsup_" + sfx, supRole);

        MvcResult opened = mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 50000}"))
                .andExpect(status().isCreated())
                .andReturn();
        long sessionId = JSON.readTree(opened.getResponse().getContentAsString()).get("id").asLong();

        // Alcance P: otro cajero (sin close-others/approve/report) no puede ver turno ajeno -> 404
        mvc.perform(get("/api/v1/cash-sessions/" + sessionId)
                        .header("Authorization", "Bearer " + otherCajToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isNotFound());

        // Cierre con diferencia -$2.000 (> tolerancia $500) sin token -> cierra con requiresDifferenceApproval=true
        mvc.perform(post("/api/v1/cash-sessions/" + sessionId + "/close")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "counts": [
                                    {"paymentMethod": "CASH", "countedAmount": 48000}
                                  ],
                                  "notes": "Faltante de 2000"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.differenceTotal").value(-2000))
                .andExpect(jsonPath("$.requiresDifferenceApproval").value(true));

        // RN-23 SOD: el propio cajero intenta aprobar su diferencia -> 422 SEGREGATION_OF_DUTIES
        mvc.perform(post("/api/v1/cash-sessions/" + sessionId + "/approve-difference")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));

        // Supervisor distinto aprueba la diferencia -> 200 OK
        mvc.perform(post("/api/v1/cash-sessions/" + sessionId + "/approve-difference")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresDifferenceApproval").value(false))
                .andExpect(jsonPath("$.differenceApprovedBy.id").value(supId));

        // Filtro withDifference=true en historial
        mvc.perform(get("/api/v1/cash-sessions?withDifference=true")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(sessionId));

        // Comprobante JSON y PDF
        mvc.perform(get("/api/v1/cash-sessions/" + sessionId + "/closing-receipt?format=json")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.id").value(sessionId))
                .andExpect(jsonPath("$.summary.expectedCash").value(50000));

        MvcResult pdfRes = mvc.perform(get("/api/v1/cash-sessions/" + sessionId + "/closing-receipt?format=pdf")
                        .header("Authorization", "Bearer " + supToken)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        byte[] pdfBytes = pdfRes.getResponse().getContentAsByteArray();
        assertThat(new String(pdfBytes, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void closeWithOverrideTokenAndAccessDeniedWithoutPermission() throws Exception {
        String sfx = uniqueSuffix();
        Long storeId = insertStore("CO" + sfx.substring(0, 6).toUpperCase(), "Local Override " + sfx);
        Long terminalId = insertTerminal(storeId, "TCO-" + sfx.substring(0, 4).toUpperCase());

        Long cashierId = insertUser("cocaj_" + sfx, "Pass#1234", null);
        Long noPermId = insertUser("conop_" + sfx, "Pass#1234", null);
        Long supId = insertUser("cosup_" + sfx, "SupPass#1", "9632");
        assignStore(cashierId, storeId, true);
        assignStore(noPermId, storeId, true);
        assignStore(supId, storeId, true);

        Long cashierRole = insertRoleWithLimits(
                "CO_CAJ_" + sfx,
                List.of("cash:session:open", "cash:session:close", "cash:session:read"),
                Map.of()
        );
        Long noPermRole = insertRoleWithLimits("CO_NOP_" + sfx, List.of(), Map.of());
        Long supRole = insertRoleWithLimits(
                "CO_SUP_" + sfx,
                List.of("cash:difference:approve"),
                Map.of()
        );
        assignRole(cashierId, cashierRole);
        assignRole(noPermId, noPermRole);
        assignRole(supId, supRole);

        String cajToken = tokenFor(cashierId, "cocaj_" + sfx, cashierRole);
        String noPermToken = tokenFor(noPermId, "conop_" + sfx, noPermRole);

        // 403 ACCESS_DENIED sin permiso cash:session:open
        mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + noPermToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 10000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        MvcResult opened = mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 50000}"))
                .andExpect(status().isCreated())
                .andReturn();
        long sessionId = JSON.readTree(opened.getResponse().getContentAsString()).get("id").asLong();

        // Autorizar override CASH_DIFFERENCE_APPROVAL con contexto {"cashSessionId": sessionId, "differenceTotal": -2000}
        MvcResult ovRes = mvc.perform(post("/api/v1/auth/overrides")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "action", "CASH_DIFFERENCE_APPROVAL",
                                "context", Map.of("cashSessionId", sessionId, "differenceTotal", -2000),
                                "supervisorUsername", "cosup_" + sfx,
                                "method", "PIN",
                                "secret", "9632",
                                "reason", "Autorizo diferencia en cierre"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String overrideToken = JSON.readTree(ovRes.getResponse().getContentAsString()).get("overrideToken").asText();

        // Cerrar con diferencia -2000 usando X-Override-Token -> queda aprobada inmediatamente
        mvc.perform(post("/api/v1/cash-sessions/" + sessionId + "/close")
                        .header("Authorization", "Bearer " + cajToken)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Override-Token", overrideToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "counts": [
                                    {"paymentMethod": "CASH", "countedAmount": 48000}
                                  ],
                                  "notes": "Faltante autorizado al cierre"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.differenceTotal").value(-2000))
                .andExpect(jsonPath("$.requiresDifferenceApproval").value(false))
                .andExpect(jsonPath("$.differenceApprovedBy.id").value(supId));
    }

    private String tokenFor(Long userId, String username, Long roleId) {
        List<String> permissions = ownerJdbc()
                .sql("""
                        SELECT p.code
                        FROM role_permissions rp
                        JOIN permissions p ON p.id = rp.permission_id
                        WHERE rp.role_id = :roleId
                        """)
                .param("roleId", roleId)
                .query(String.class)
                .list();
        return jwtTokenService.issueAccessToken(userId, username, TokenScope.FULL, permissions).accessToken();
    }

    private Long insertStore(String code, String name) {
        Long companyId = ownerJdbc()
                .sql("SELECT id FROM company ORDER BY id LIMIT 1")
                .query(Long.class)
                .optional()
                .orElseGet(() -> ownerJdbc().sql("""
                        INSERT INTO company (rut, legal_name, trade_name, business_activity, address, commune, city, timezone, currency)
                        VALUES ('76123456-K', 'Minimarket Test SpA', 'Minimarket Test', 'Comercio al por menor', 'Av. Test 123', 'Santiago', 'Santiago', 'America/Santiago', 'CLP')
                        RETURNING id
                        """)
                        .query(Long.class)
                        .single());
        return ownerJdbc().sql("""
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

    private Long insertTerminal(Long storeId, String code) {
        return ownerJdbc().sql("""
                INSERT INTO terminals (store_id, code, name, active)
                VALUES (:storeId, :code, :name, true)
                RETURNING id
                """)
                .param("storeId", storeId)
                .param("code", code)
                .param("name", "Caja " + code)
                .query(Long.class)
                .single();
    }

    private Long insertUser(String username, String password, String pin) {
        return ownerJdbc().sql("""
                INSERT INTO users (username, password_hash, pin_hash, full_name, email, active)
                VALUES (:username, :pwHash, :pinHash, :fullName, :email, true)
                RETURNING id
                """)
                .param("username", username)
                .param("pwHash", passwordEncoder.encode(password))
                .param("pinHash", pin != null ? passwordEncoder.encode(pin) : null)
                .param("fullName", "Test " + username)
                .param("email", username + "@test.local")
                .query(Long.class)
                .single();
    }

    private void assignStore(Long userId, Long storeId, boolean isDefault) {
        ownerJdbc().sql("""
                INSERT INTO user_stores (user_id, store_id, is_default)
                VALUES (:userId, :storeId, :isDefault)
                """)
                .param("userId", userId)
                .param("storeId", storeId)
                .param("isDefault", isDefault)
                .update();
    }

    private Long insertRoleWithLimits(String code, List<String> permissions, Map<String, BigDecimal> limits) {
        Long roleId = ownerJdbc().sql("""
                INSERT INTO roles (code, name, description, system)
                VALUES (:code, :name, 'Rol test T29', false)
                RETURNING id
                """)
                .param("code", code)
                .param("name", code)
                .query(Long.class)
                .single();
        for (String perm : permissions) {
            ownerJdbc().sql("""
                    INSERT INTO role_permissions (role_id, permission_id)
                    SELECT :roleId, id FROM permissions WHERE code = :perm
                    """)
                    .param("roleId", roleId)
                    .param("perm", perm)
                    .update();
        }
        for (Map.Entry<String, BigDecimal> entry : limits.entrySet()) {
            ownerJdbc().sql("""
                    INSERT INTO role_limits (role_id, limit_code, value)
                    VALUES (:roleId, :code, :val)
                    """)
                    .param("roleId", roleId)
                    .param("code", entry.getKey())
                    .param("val", entry.getValue())
                    .update();
        }
        return roleId;
    }

    private void assignRole(Long userId, Long roleId) {
        ownerJdbc().sql("INSERT INTO user_roles (user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
