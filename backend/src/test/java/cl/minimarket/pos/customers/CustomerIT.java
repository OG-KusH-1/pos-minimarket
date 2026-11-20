package cl.minimarket.pos.customers;

import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del módulo {@code customers} (T69):
 * <ul>
 *   <li>CRUD de clientes, validación de RUT (RN-30 -> {@code RUT_INVALID}), unicidad ({@code DUPLICATED_VALUE}),
 *       activar/desactivar ({@code ENTITY_IN_USE} con deuda).</li>
 *   <li>Cupo de crédito ({@code CREDIT_LIMIT_MAX_ASSIGN}), {@link CustomerCreditApi} (RN-15,
 *       {@code CREDIT_NOT_ENABLED}, {@code CREDIT_LIMIT_EXCEEDED}).</li>
 *   <li>Abonos ({@code CASH} y {@code TRANSFER}, {@code PAYMENT_EXCEEDS_DEBT}, idempotencia) y aporte
 *       al esperado del turno mediante {@code CashFlowContributor}.</li>
 *   <li>Ajustes de cuenta corriente ({@code CREDIT_ADJUSTMENT_MAX_AMOUNT} -> {@code CREDIT_LIMIT_EXCEEDED})
 *       y auditoría {@code CUSTOMER_ACCOUNT_ADJUSTED}.</li>
 * </ul>
 */
class CustomerIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    CustomerCreditApi customerCreditApi;

    @Test
    void crudCreditRulesPaymentsCashFlowAndAdjustmentsWorkEndToEnd() throws Exception {
        String sfx = uniqueSuffix();
        Long storeId = insertStore("CU" + sfx.substring(0, 6).toUpperCase(), "Local Clientes " + sfx);
        Long terminalId = insertTerminal(storeId, "TCU-" + sfx.substring(0, 4).toUpperCase());
        Long userId = insertUser("cust_usr_" + sfx, "Pass#1234");
        assignStore(userId, storeId, true);

        Long roleId = insertRoleWithLimits(
                "CUST_ROLE_" + sfx,
                List.of(
                        "customers:customer:read",
                        "customers:customer:manage",
                        "customers:credit:sell",
                        "customers:credit:payment",
                        "customers:credit:manage-limit",
                        "cash:session:open",
                        "cash:session:read"
                ),
                Map.of(
                        "CREDIT_LIMIT_MAX_ASSIGN", new BigDecimal("100000"),
                        "CREDIT_ADJUSTMENT_MAX_AMOUNT", new BigDecimal("50000")
                )
        );
        assignRole(userId, roleId);
        String token = tokenFor(userId, "cust_usr_" + sfx, roleId);

        // 1. RUT inválido -> 422 RUT_INVALID
        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rut": "12345678-0",
                                  "name": "Cliente Inválido"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RUT_INVALID"));

        // 2. Crear cliente con RUT válido (sin formato) -> 201 normalizado
        String validRut = validRutFromNumber(10_000_000L + (Math.abs(sfx.hashCode()) % 80_000_000L));
        String unformattedRut = validRut.replace("-", "");
        MvcResult createdRes = mvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rut": "%s",
                                  "name": "María González %s",
                                  "email": "maria.%s@test.local",
                                  "phone": "+56912345678",
                                  "address": "Calle 123",
                                  "notes": "Cliente frecuente"
                                }
                                """.formatted(unformattedRut, sfx, sfx)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rut").value(validRut))
                .andExpect(jsonPath("$.creditEnabled").value(false))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        JsonNode createdJson = JSON.readTree(createdRes.getResponse().getContentAsString());
        long customerId = createdJson.get("id").asLong();
        long version = createdJson.get("version").asLong();

        // 3. RUT duplicado -> 409 DUPLICATED_VALUE
        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rut": "%s",
                                  "name": "Otro Cliente"
                                }
                                """.formatted(validRut)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATED_VALUE"));

        // 4. Listar con búsqueda q y sort
        mvc.perform(get("/api/v1/customers")
                        .header("Authorization", "Bearer " + token)
                        .param("q", sfx)
                        .param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(customerId));

        // 5. Actualizar cliente
        mvc.perform(put("/api/v1/customers/" + customerId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rut": "%s",
                                  "name": "María González Editada %s",
                                  "email": "maria.edit.%s@test.local",
                                  "version": %d
                                }
                                """.formatted(validRut, sfx, sfx, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("María González Editada " + sfx));

        // 6. Cargo sin crédito habilitado -> 422 CREDIT_NOT_ENABLED
        Long dummySessionId = insertClosedOrOpenDummySession(storeId, terminalId, userId, sfx);
        Long saleId = insertDummySale(storeId, terminalId, dummySessionId, userId, customerId, sfx);

        runAsUser(userId, () -> assertThatThrownBy(() ->
                customerCreditApi.charge(customerId, new BigDecimal("10000"), saleId, storeId))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.CREDIT_NOT_ENABLED));

        // 7. Configurar crédito sobre el límite del rol -> 422 CREDIT_LIMIT_EXCEEDED
        mvc.perform(put("/api/v1/customers/" + customerId + "/credit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "creditEnabled": true,
                                  "creditLimit": 150000,
                                  "reason": "Aumento sobre límite"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"));

        // 8. Configurar crédito dentro del límite -> 200 (cupo 50.000)
        mvc.perform(put("/api/v1/customers/" + customerId + "/credit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "creditEnabled": true,
                                  "creditLimit": 50000,
                                  "reason": "Habilitación inicial"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditEnabled").value(true))
                .andExpect(jsonPath("$.creditLimit").value(50000))
                .andExpect(jsonPath("$.availableCredit").value(50000));

        // 9. Cargo dentro del cupo (45.000) y luego cargo que excede (10.000) -> RN-15 CREDIT_LIMIT_EXCEEDED
        runAsUser(userId, () -> {
            customerCreditApi.charge(customerId, new BigDecimal("45000"), saleId, storeId);
            assertThatThrownBy(() ->
                    customerCreditApi.charge(customerId, new BigDecimal("10000"), saleId, storeId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getCode())
                    .isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED);
        });

        // 10. No se puede desactivar con deuda vigente -> 409 ENTITY_IN_USE
        mvc.perform(post("/api/v1/customers/" + customerId + "/deactivate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

        // 11. Abono excede deuda -> 422 PAYMENT_EXCEEDS_DEBT
        mvc.perform(post("/api/v1/customers/" + customerId + "/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 50000,
                                  "paymentMethod": "TRANSFER",
                                  "reference": "TRF-001"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXCEEDS_DEBT"));

        // 12. Abrir turno de caja con 50.000, registrar abono CASH de 20.000 y verificar esperado = 70.000
        MvcResult openSessionRes = mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 50000}"))
                .andExpect(status().isCreated())
                .andReturn();
        long openSessionId = JSON.readTree(openSessionRes.getResponse().getContentAsString()).get("id").asLong();

        UUID paymentIdemKey = UUID.randomUUID();
        mvc.perform(post("/api/v1/customers/" + customerId + "/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", paymentIdemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 20000,
                                  "paymentMethod": "CASH",
                                  "notes": "Abono parcial"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movementType").value("PAYMENT"))
                .andExpect(jsonPath("$.amount").value(-20000))
                .andExpect(jsonPath("$.balanceAfter").value(25000));

        // Reintento idempotente
        mvc.perform(post("/api/v1/customers/" + customerId + "/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", paymentIdemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 20000,
                                  "paymentMethod": "CASH",
                                  "notes": "Abono parcial"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        // El abono en efectivo suma al esperado del turno (50.000 + 20.000 = 70.000)
        mvc.perform(get("/api/v1/cash-sessions/" + openSessionId + "/summary")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCash").value(70000));

        // 13. Ajuste sobre CREDIT_ADJUSTMENT_MAX_AMOUNT (50.000) -> 422 CREDIT_LIMIT_EXCEEDED
        mvc.perform(post("/api/v1/customers/" + customerId + "/adjustments")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 60000,
                                  "reason": "Ajuste sobre el límite"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"));

        // 14. Ajuste válido (-5.000) -> 201 y auditoría CUSTOMER_ACCOUNT_ADJUSTED
        mvc.perform(post("/api/v1/customers/" + customerId + "/adjustments")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": -5000,
                                  "reason": "Condonación por diferencia"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movementType").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.amount").value(-5000))
                .andExpect(jsonPath("$.balanceAfter").value(20000));

        long auditCount = appJdbc().sql("""
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'CUSTOMER_ACCOUNT_ADJUSTED'
                  AND entity_type = 'customer'
                  AND entity_id = :entityId
                """)
                .param("entityId", String.valueOf(customerId))
                .query(Long.class)
                .single();
        assertThat(auditCount).isEqualTo(1L);

        // 15. Estado de cuenta / cartola paginada
        mvc.perform(get("/api/v1/customers/" + customerId + "/account")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.creditBalance").value(20000))
                .andExpect(jsonPath("$.movements.totalElements").value(3));
    }

    private static void runAsUser(Long userId, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "N/A", List.of())
        );
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String validRutFromNumber(long body) {
        int sum = 0;
        int factor = 2;
        for (long rest = body; rest > 0; rest /= 10) {
            sum += (int) (rest % 10) * factor;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int dv = 11 - (sum % 11);
        char dvChar = dv == 11 ? '0' : (dv == 10 ? 'K' : (char) ('0' + dv));
        return body + "-" + dvChar;
    }

    private String tokenFor(Long userId, String username, Long roleId) {
        List<String> perms = ownerJdbc().sql("""
                SELECT p.code
                FROM permissions p
                JOIN role_permissions rp ON rp.permission_id = p.id
                WHERE rp.role_id = :roleId
                """)
                .param("roleId", roleId)
                .query(String.class)
                .list();
        return jwtTokenService.issueAccessToken(userId, username, TokenScope.FULL, perms).accessToken();
    }

    private Long insertStore(String code, String name) {
        Long companyId = ownerJdbc().sql("SELECT id FROM company ORDER BY id ASC LIMIT 1")
                .query(Long.class)
                .optional()
                .orElseGet(() -> ownerJdbc().sql("""
                        INSERT INTO company (rut, legal_name, trade_name, address, commune, city)
                        VALUES ('76123456-K', 'Empresa Test SpA', 'Minimarket Test', 'Av. Test 1', 'Santiago', 'Santiago')
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

    private Long insertUser(String username, String password) {
        return ownerJdbc().sql("""
                INSERT INTO users (username, password_hash, full_name, email, active)
                VALUES (:username, :pwHash, :fullName, :email, true)
                RETURNING id
                """)
                .param("username", username)
                .param("pwHash", passwordEncoder.encode(password))
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
                VALUES (:code, :name, 'Rol test T69', false)
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

    private Long insertClosedOrOpenDummySession(Long storeId, Long terminalId, Long userId, String sfx) {
        return ownerJdbc().sql("""
                INSERT INTO cash_sessions (number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount)
                VALUES (:number, :storeId, :terminalId, :userId, 'CLOSED', :bDate, 0)
                RETURNING id
                """)
                .param("number", "TU-T69-" + sfx)
                .param("storeId", storeId)
                .param("terminalId", terminalId)
                .param("userId", userId)
                .param("bDate", LocalDate.now())
                .query(Long.class)
                .single();
    }

    private Long insertDummySale(Long storeId, Long terminalId, Long sessionId, Long userId, Long customerId, String sfx) {
        return ownerJdbc().sql("""
                INSERT INTO sales (
                    number, store_id, terminal_id, cash_session_id, cashier_user_id, customer_id,
                    status, business_date, idempotency_key, subtotal_gross, total_gross, net_total,
                    tax_total, total_payable, paid_total
                )
                VALUES (
                    :number, :storeId, :terminalId, :sessionId, :userId, :customerId,
                    'COMPLETED', :bDate, :idemKey, 45000, 45000, 37815,
                    7185, 45000, 45000
                )
                RETURNING id
                """)
                .param("number", "V-T69-" + sfx)
                .param("storeId", storeId)
                .param("terminalId", terminalId)
                .param("sessionId", sessionId)
                .param("userId", userId)
                .param("customerId", customerId)
                .param("bDate", LocalDate.now())
                .param("idemKey", UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
