package cl.minimarket.pos.billing.web;

import cl.minimarket.pos.billing.TaxDocumentStatus;
import cl.minimarket.pos.billing.TaxDocumentType;
import cl.minimarket.pos.sales.SaleCompletedEvent;
import cl.minimarket.pos.sales.SaleReturnedEvent;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del módulo {@code billing} ({@code PLAN.md} H.3 T70, {@code HU-POS-12}).
 */
class TaxDocumentControllerIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    BusinessClock clock;

    private String suffix;
    private long storeId;
    private long cashierId;
    private long otherCashierId;
    private long terminalId;
    private long cashSessionId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        cashierId = TestData.insertUser(jdbc);
        otherCashierId = TestData.insertUser(jdbc);
        assignStore(cashierId, storeId);
        assignStore(otherCashierId, storeId);
        terminalId = insertTerminal(storeId, "TERM-" + suffix);
        cashSessionId = insertCashSession(storeId, terminalId, cashierId, "TU-" + suffix);
    }

    @Test
    void shouldEmitBoleta39WithCorrelativeFolioOnSaleCompletedEvent() throws Exception {
        long sale1Id = insertSale("V1-" + suffix, cashierId);
        long sale2Id = insertSale("V2-" + suffix, cashierId);

        Instant now = clock.now();
        LocalDate today = clock.businessDate(now);

        tx.executeWithoutResult(status -> {
            events.publishEvent(buildSaleCompletedEvent(sale1Id, "V1-" + suffix, now, today));
            events.publishEvent(buildSaleCompletedEvent(sale2Id, "V2-" + suffix, now, today));
        });

        awaitUntil(() -> countBySaleId(sale1Id) == 1 && countBySaleId(sale2Id) == 1);

        MvcResult res1 = mvc.perform(get("/api/v1/sales/{id}/tax-document", sale1Id)
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_POS_OPERATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("BOLETA"))
                .andExpect(jsonPath("$.siiTypeCode").value(39))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.provider").value("SIMULATED"))
                .andExpect(jsonPath("$.providerTrackId").isNotEmpty())
                .andExpect(jsonPath("$.stampText").isNotEmpty())
                .andReturn();

        MvcResult res2 = mvc.perform(get("/api/v1/sales/{id}/tax-document", sale2Id)
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_POS_OPERATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siiTypeCode").value(39))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andReturn();

        JsonNode doc1 = jsonMapper.readTree(res1.getResponse().getContentAsString());
        JsonNode doc2 = jsonMapper.readTree(res2.getResponse().getContentAsString());

        long folio1 = doc1.get("folio").asLong();
        long folio2 = doc2.get("folio").asLong();
        assertThat(folio2).isEqualTo(folio1 + 1);

        long doc1Id = doc1.get("id").asLong();
        mvc.perform(get("/api/v1/tax-documents/{id}", doc1Id)
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_TAX_DOCUMENT_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folio").value(folio1));

        mvc.perform(get("/api/v1/tax-documents")
                        .param("documentType", TaxDocumentType.BOLETA.name())
                        .param("status", TaxDocumentStatus.ISSUED.name())
                        .param("folio", String.valueOf(folio2))
                        .param("sort", "folio,desc")
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_TAX_DOCUMENT_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].folio").value(folio2));
    }

    @Test
    void shouldEmitCreditNote61OnSaleReturnedEvent() throws Exception {
        long saleId = insertSale("VR-" + suffix, cashierId);
        Instant now = clock.now();
        LocalDate today = clock.businessDate(now);

        tx.executeWithoutResult(status ->
                events.publishEvent(buildSaleCompletedEvent(saleId, "VR-" + suffix, now, today)));
        awaitUntil(() -> countBySaleId(saleId) == 1);

        long saleReturnId = insertSaleReturn("DR-" + suffix, saleId, cashierId);
        tx.executeWithoutResult(status ->
                events.publishEvent(new SaleReturnedEvent(
                        saleReturnId, "DR-" + suffix, saleId, "VR-" + suffix, "RETURN",
                        storeId, terminalId, cashSessionId, cashierId, now, today,
                        new BigDecimal("1190"),
                        List.of(new SaleReturnedEvent.Line(
                                1L, 10L, "SKU-" + suffix, "Producto Test",
                                BigDecimal.ONE, new BigDecimal("1190")))
                )));
        awaitUntil(() -> countBySaleReturnId(saleReturnId) == 1);

        MvcResult result = mvc.perform(get("/api/v1/sale-returns/{id}/tax-document", saleReturnId)
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_SALE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("CREDIT_NOTE"))
                .andExpect(jsonPath("$.siiTypeCode").value(61))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.saleReturnId").value(saleReturnId))
                .andExpect(jsonPath("$.totalAmount").value(1190))
                .andReturn();

        JsonNode nc = jsonMapper.readTree(result.getResponse().getContentAsString());
        Long referencedId = jdbc.sql("SELECT referenced_document_id FROM tax_documents WHERE id = :id")
                .param("id", nc.get("id").asLong())
                .query(Long.class)
                .single();
        assertThat(referencedId).isNotNull();
    }

    @Test
    void shouldReturn204WhenDocumentNotYetIssuedAndEnforceScopeP() throws Exception {
        long saleId = insertSale("V204-" + suffix, cashierId);
        long saleReturnId = insertSaleReturn("D204-" + suffix, saleId, cashierId);

        mvc.perform(get("/api/v1/sales/{id}/tax-document", saleId)
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_POS_OPERATE)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/sale-returns/{id}/tax-document", saleReturnId)
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_SALE_READ)))
                .andExpect(status().isNoContent());

        // Alcance P: otro cajero con el set realista de CAJ (sales:pos:operate + sales:sale:read,
        // que en D.1 es alcance "P") recibe 404 NOT_FOUND: sales:sale:read no da alcance global.
        mvc.perform(get("/api/v1/sales/{id}/tax-document", saleId)
                        .header("X-Store-Id", storeId)
                        .with(auth(otherCashierId, Permissions.SALES_POS_OPERATE, Permissions.SALES_SALE_READ)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn404ForTaxDocumentOfAnotherCashierSaleReturn() throws Exception {
        // Venta origen emitida por otro cajero: la devolución es ajena para cashierId.
        long saleId = insertSale("VOTR-" + suffix, otherCashierId);
        Instant now = clock.now();
        LocalDate today = clock.businessDate(now);

        tx.executeWithoutResult(status ->
                events.publishEvent(buildSaleCompletedEvent(saleId, "VOTR-" + suffix, otherCashierId, now, today)));
        awaitUntil(() -> countBySaleId(saleId) == 1);

        long saleReturnId = insertSaleReturn("DOTR-" + suffix, saleId, otherCashierId);
        tx.executeWithoutResult(status ->
                events.publishEvent(new SaleReturnedEvent(
                        saleReturnId, "DOTR-" + suffix, saleId, "VOTR-" + suffix, "RETURN",
                        storeId, terminalId, cashSessionId, otherCashierId, now, today,
                        new BigDecimal("1190"),
                        List.of(new SaleReturnedEvent.Line(
                                1L, 10L, "SKU-" + suffix, "Producto Test",
                                BigDecimal.ONE, new BigDecimal("1190")))
                )));
        awaitUntil(() -> countBySaleReturnId(saleReturnId) == 1);

        // El dueño de la venta origen sí ve la nota de crédito de la devolución.
        mvc.perform(get("/api/v1/sale-returns/{id}/tax-document", saleReturnId)
                        .header("X-Store-Id", storeId)
                        .with(auth(otherCashierId, Permissions.SALES_SALE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("CREDIT_NOTE"))
                .andExpect(jsonPath("$.siiTypeCode").value(61))
                .andExpect(jsonPath("$.saleReturnId").value(saleReturnId));

        // Alcance P: una devolución de venta ajena responde 404 para el cajero.
        mvc.perform(get("/api/v1/sale-returns/{id}/tax-document", saleReturnId)
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_SALE_READ)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRetryDocumentInErrorAndAudit() throws Exception {
        long saleId = insertSale("VERR-" + suffix, cashierId);
        long uniqueFolio = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);

        long docId = ownerJdbc().sql("""
                INSERT INTO tax_documents (
                    store_id, document_type, sii_type_code, folio, sale_id,
                    status, provider, net_amount, exempt_amount, tax_amount, total_amount,
                    payload, attempts, last_error
                ) VALUES (
                    :storeId, 'BOLETA', 39, :folio, :saleId,
                    'ERROR', 'SIMULATED', 1000, 0, 190, 1190,
                    '{}'::jsonb, 1, 'Timeout SII simulado'
                ) RETURNING id
                """)
                .param("storeId", storeId)
                .param("folio", uniqueFolio)
                .param("saleId", saleId)
                .query(Long.class)
                .single();

        mvc.perform(post("/api/v1/tax-documents/{id}/retry", docId)
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_TAX_DOCUMENT_RETRY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(docId))
                .andExpect(jsonPath("$.folio").value(uniqueFolio))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.lastError").doesNotExist())
                .andExpect(jsonPath("$.providerTrackId").isNotEmpty())
                .andExpect(jsonPath("$.issuedAt").isNotEmpty());

        mvc.perform(post("/api/v1/tax-documents/{id}/retry", docId)
                        .header("X-Store-Id", storeId)
                        .with(auth(cashierId, Permissions.SALES_TAX_DOCUMENT_RETRY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        long auditCount = jdbc.sql("""
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'TAX_DOCUMENT_RETRIED' AND entity_type = 'tax_document' AND entity_id = :id
                """)
                .param("id", String.valueOf(docId))
                .query(Long.class)
                .single();
        assertThat(auditCount).isEqualTo(1L);
    }

    private SaleCompletedEvent buildSaleCompletedEvent(long saleId, String number, Instant now, LocalDate today) {
        return buildSaleCompletedEvent(saleId, number, cashierId, now, today);
    }

    /**
     * Construye un {@link SaleCompletedEvent} con un cajero explícito, para casos donde la
     * venta origen pertenece a otro usuario (alcance "P").
     */
    private SaleCompletedEvent buildSaleCompletedEvent(
            long saleId, String number, long cashierUserId, Instant now, LocalDate today) {
        return new SaleCompletedEvent(
                saleId, number, storeId, terminalId, cashSessionId, cashierUserId, null,
                now, today,
                new BigDecimal("1190"), BigDecimal.ZERO, new BigDecimal("1190"),
                new BigDecimal("1000"), new BigDecimal("190"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("1190"),
                List.of(new SaleCompletedEvent.Line(
                        1, 10L, "SKU-" + suffix, "Producto Test",
                        BigDecimal.ONE, new BigDecimal("1190"), new BigDecimal("1190"),
                        new BigDecimal("1000"), new BigDecimal("190"), BigDecimal.ZERO))
        );
    }

    private int countBySaleId(long saleId) {
        return jdbc.sql("SELECT COUNT(*) FROM tax_documents WHERE sale_id = :saleId")
                .param("saleId", saleId)
                .query(Integer.class)
                .single();
    }

    private int countBySaleReturnId(long saleReturnId) {
        return jdbc.sql("SELECT COUNT(*) FROM tax_documents WHERE sale_return_id = :saleReturnId")
                .param("saleReturnId", saleReturnId)
                .query(Integer.class)
                .single();
    }

    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        assertThat(condition.getAsBoolean()).as("La condición asíncrona no se cumplió a tiempo").isTrue();
    }

    private void assignStore(long userId, long storeId) {
        jdbc.sql("INSERT INTO user_stores (user_id, store_id) VALUES (:u, :s) ON CONFLICT DO NOTHING")
                .param("u", userId)
                .param("s", storeId)
                .update();
    }

    private long insertTerminal(long storeId, String code) {
        return jdbc.sql("INSERT INTO terminals (store_id, code, name, active) VALUES (:s, :c, :n, true) RETURNING id")
                .param("s", storeId)
                .param("c", code)
                .param("n", "Caja " + code)
                .query(Long.class)
                .single();
    }

    private long insertCashSession(long storeId, long terminalId, long cashierId, String number) {
        return jdbc.sql("""
                INSERT INTO cash_sessions (number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount)
                VALUES (:num, :s, :t, :c, 'OPEN', CURRENT_DATE, 50000)
                RETURNING id
                """)
                .param("num", number)
                .param("s", storeId)
                .param("t", terminalId)
                .param("c", cashierId)
                .query(Long.class)
                .single();
    }

    private long insertSale(String number, long cashierUserId) {
        return jdbc.sql("""
                INSERT INTO sales (
                    number, store_id, terminal_id, cash_session_id, cashier_user_id,
                    status, sold_at, business_date, idempotency_key,
                    subtotal_gross, discount_total, total_gross, net_total, tax_total,
                    exempt_total, rounding_adjustment, total_payable, paid_total, change_total, age_confirmed
                ) VALUES (
                    :num, :s, :t, :cs, :cu,
                    'COMPLETED', now(), CURRENT_DATE, :idem,
                    1190, 0, 1190, 1000, 190,
                    0, 0, 1190, 1190, 0, false
                ) RETURNING id
                """)
                .param("num", number)
                .param("s", storeId)
                .param("t", terminalId)
                .param("cs", cashSessionId)
                .param("cu", cashierUserId)
                .param("idem", UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private long insertSaleReturn(String number, long saleId, long createdBy) {
        return jdbc.sql("""
                INSERT INTO sale_returns (
                    number, sale_id, return_type, store_id, terminal_id, cash_session_id,
                    reason_code, refund_total, idempotency_key, created_by
                ) VALUES (
                    :num, :saleId, 'RETURN', :s, :t, :cs,
                    'DEFECTIVE', 1190, :idem, :cb
                ) RETURNING id
                """)
                .param("num", number)
                .param("saleId", saleId)
                .param("s", storeId)
                .param("t", terminalId)
                .param("cs", cashSessionId)
                .param("idem", UUID.randomUUID())
                .param("cb", createdBy)
                .query(Long.class)
                .single();
    }

    private static RequestPostProcessor auth(long userId, String... permissions) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            authorities[i] = new SimpleGrantedAuthority(permissions[i]);
        }
        return user(String.valueOf(userId)).authorities(authorities);
    }
}
