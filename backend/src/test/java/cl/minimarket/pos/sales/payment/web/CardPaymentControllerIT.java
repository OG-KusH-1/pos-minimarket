package cl.minimarket.pos.sales.payment.web;

import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de {@code /api/v1/pos/card-payments} ({@code PLAN.md} H.3 T33).
 */
class CardPaymentControllerIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    private String suffix;
    private long storeId;
    private long userId;
    private long terminalId;
    private long terminalWithoutSessionId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        userId = TestData.insertUser(jdbc);
        jdbc.sql("INSERT INTO user_stores (user_id, store_id) VALUES (:u, :s) ON CONFLICT DO NOTHING")
                .param("u", userId)
                .param("s", storeId)
                .update();

        terminalId = insertTerminal(storeId, "CAJA-1-" + suffix);
        terminalWithoutSessionId = insertTerminal(storeId, "CAJA-2-" + suffix);
        openCashSession(storeId, terminalId, userId);
    }

    @Test
    void authorizeApprovedPersistsOnlyLast4AndNeverPan() throws Exception {
        UUID key = UUID.randomUUID();
        MvcResult result = mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "DEBIT", "amount": 5000 }
                                """)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.cardBrand").value("REDCOMPRA"))
                .andExpect(jsonPath("$.last4").isString())
                .andReturn();

        JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
        long authId = body.get("id").asLong();
        String last4 = body.get("last4").asText();
        assertThat(last4).hasSize(4);

        var row = jdbc.sql("""
                        SELECT status, method, amount, card_last4, gateway_reference, authorization_code, sale_id
                        FROM card_authorizations WHERE id = :id
                        """)
                .param("id", authId)
                .query((rs, rowNum) -> new AuthRow(
                        rs.getString("status"),
                        rs.getString("method"),
                        rs.getBigDecimal("amount"),
                        rs.getString("card_last4"),
                        rs.getString("gateway_reference"),
                        rs.getString("authorization_code"),
                        rs.getObject("sale_id", Long.class)
                ))
                .single();

        assertThat(row.status()).isEqualTo("APPROVED");
        assertThat(row.method()).isEqualTo("DEBIT");
        assertThat(row.amount()).isEqualByComparingTo("5000");
        assertThat(row.cardLast4()).isEqualTo(last4).hasSize(4);
        assertThat(row.gatewayReference()).isNotBlank();
        assertThat(row.authorizationCode()).isNotBlank();
        assertThat(row.saleId()).isNull();
    }

    @Test
    void authorizeDeclinedWhenAmountEndsIn13AndAllowsRetryWithSameIdempotencyKey() throws Exception {
        UUID key = UUID.randomUUID();

        mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "CREDIT", "amount": 5013 }
                                """)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));

        Integer declinedCount = jdbc.sql("""
                        SELECT count(*) FROM card_authorizations
                        WHERE store_id = :s AND terminal_id = :t AND amount = 5013 AND status = 'DECLINED'
                        """)
                .param("s", storeId)
                .param("t", terminalId)
                .query(Integer.class)
                .single();
        assertThat(declinedCount).isEqualTo(1);

        // Tras un 422, IdempotencyFilter libera la clave y permite reintentar con monto corregido
        mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "CREDIT", "amount": 5000 }
                                """)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void authorizeErrorWhenAmountEndsIn99() throws Exception {
        UUID key = UUID.randomUUID();

        mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "DEBIT", "amount": 1099 }
                                """)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        Integer errorCount = jdbc.sql("""
                        SELECT count(*) FROM card_authorizations
                        WHERE store_id = :s AND terminal_id = :t AND amount = 1099 AND status = 'ERROR'
                        """)
                .param("s", storeId)
                .param("t", terminalId)
                .query(Integer.class)
                .single();
        assertThat(errorCount).isEqualTo(1);
    }

    @Test
    void idempotencyReplaysSameResponseAndRejectsDifferentBody() throws Exception {
        UUID key = UUID.randomUUID();
        String payload = """
                { "method": "CREDIT", "amount": 8500 }
                """;

        MvcResult first = mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult replay = mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(jsonMapper.readTree(replay.getResponse().getContentAsString()))
                .isEqualTo(jsonMapper.readTree(first.getResponse().getContentAsString()));

        Integer count = jdbc.sql("SELECT count(*) FROM card_authorizations WHERE idempotency_key = :k")
                .param("k", key)
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(1);

        // Misma clave con distinto monto -> 409 IDEMPOTENCY_CONFLICT
        mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "CREDIT", "amount": 9000 }
                                """)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void requiresOpenCashSessionAndTerminalHeader() throws Exception {
        // Sin turno abierto -> 422 CASH_SESSION_NOT_OPEN
        mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalWithoutSessionId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "DEBIT", "amount": 3000 }
                                """)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_NOT_OPEN"));

        // Sin X-Terminal-Id en authorize -> 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "DEBIT", "amount": 3000 }
                                """)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Sin X-Terminal-Id en void -> 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/pos/card-payments/{id}/void", 1L)
                        .header("X-Store-Id", storeId)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsRequestsWithoutPosOperatePermission() throws Exception {
        mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "DEBIT", "amount": 3000 }
                                """)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_SALE_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(post("/api/v1/pos/card-payments/{id}/void", 1L)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_SALE_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void voidUnusedAuthorizationSucceedsAndRejectsSecondVoid() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "CREDIT", "amount": 7500 }
                                """)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isCreated())
                .andReturn();

        long authId = jsonMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/pos/card-payments/{id}/void", authId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(authId))
                .andExpect(jsonPath("$.status").value("VOIDED"));

        // Segunda anulación -> 409 INVALID_STATE_TRANSITION
        mvc.perform(post("/api/v1/pos/card-payments/{id}/void", authId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    private long insertTerminal(long storeId, String code) {
        return jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name)
                        VALUES (:s, :c, :n) RETURNING id
                        """)
                .param("s", storeId)
                .param("c", code)
                .param("n", "Terminal " + code)
                .query(Long.class)
                .single();
    }

    private void openCashSession(long storeId, long terminalId, long cashierUserId) {
        jdbc.sql("""
                        INSERT INTO cash_sessions (number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount)
                        VALUES (:num, :s, :t, :u, 'OPEN', CURRENT_DATE, 10000)
                        """)
                .param("num", "TU-" + UUID.randomUUID().toString().substring(0, 8))
                .param("s", storeId)
                .param("t", terminalId)
                .param("u", cashierUserId)
                .update();
    }

    private record AuthRow(
            String status,
            String method,
            BigDecimal amount,
            String cardLast4,
            String gatewayReference,
            String authorizationCode,
            Long saleId
    ) {
    }
}
