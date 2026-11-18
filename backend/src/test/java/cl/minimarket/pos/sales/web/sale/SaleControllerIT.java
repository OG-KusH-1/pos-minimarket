package cl.minimarket.pos.sales.web.sale;

import cl.minimarket.pos.cash.CashFlowContributor;
import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.sales.SaleLookupApi;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de ventas POS ({@code /api/v1/sales}, {@code PLAN.md} H.3 T36,
 * {@code API.md} F.4 flujos 1, 2 y 3).
 */
class SaleControllerIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    StockApi stockApi;

    @Autowired
    SaleLookupApi saleLookupApi;

    @Autowired
    List<CashFlowContributor> cashFlowContributors;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    PasswordEncoder passwordEncoder;

    private String suffix;
    private long storeId;
    private long cashierId;
    private long otherCashierId;
    private long supervisorId;
    private String supervisorUsername;
    private long terminalId;
    private long otherTerminalId;
    private long cashSessionId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        cashierId = insertUserWithRole("caj1_" + suffix, "Cajero Uno", null, "CASHIER");
        otherCashierId = insertUserWithRole("caj2_" + suffix, "Cajero Dos", null, "CASHIER");
        supervisorUsername = "sup_" + suffix;
        supervisorId = insertUserWithRole(supervisorUsername, "Supervisor Prueba", "1234", "SUPERVISOR");

        terminalId = insertTerminal(storeId, "CAJA-1-" + suffix);
        otherTerminalId = insertTerminal(storeId, "CAJA-2-" + suffix);
        cashSessionId = openCashSession(storeId, terminalId, cashierId);
        openCashSession(storeId, otherTerminalId, otherCashierId);

        categoryId = createCategory("Cat Venta " + suffix);
    }

    @Test
    void flow1MixedDebitAndCashWithRoundingUpdatesStockAndCashFlow() throws Exception {
        long productId = createProduct("VTA1-" + suffix, "Café Molido 250g " + suffix,
                "UNIT", "UNIT", 5995, new BigDecimal("3000.0000"), null, null);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("3000.0000"));

        MvcResult cardRes = mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "DEBIT", "amount": 4000 }
                                """)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andReturn();
        long cardAuthId = jsonMapper.readTree(cardRes.getResponse().getContentAsString()).get("id").asLong();

        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    { "productId": %d, "quantity": 1 }
                                  ],
                                  "payments": [
                                    { "method": "DEBIT", "amount": 4000, "cardAuthorizationId": %d },
                                    { "method": "CASH", "tenderedAmount": 5000 }
                                  ]
                                }
                                """.formatted(productId, cardAuthId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.subtotalGross").value(5995))
                .andExpect(jsonPath("$.totalGross").value(5995))
                .andExpect(jsonPath("$.roundingAdjustment").value(-5))
                .andExpect(jsonPath("$.totalPayable").value(5990))
                .andExpect(jsonPath("$.paidTotal").value(9000))
                .andExpect(jsonPath("$.changeTotal").value(3010))
                .andReturn();

        JsonNode body = jsonMapper.readTree(saleRes.getResponse().getContentAsString());
        long saleId = body.get("id").asLong();
        assertThat(body.get("paidTotal").decimalValue().subtract(body.get("changeTotal").decimalValue()))
                .isEqualByComparingTo(body.get("totalPayable").decimalValue());

        // Verificar sale_payments en BD: efectivo aplicado = 1990, entregado = 5000, vuelto = 3010
        BigDecimal cashAmount = jdbc.sql("""
                        SELECT amount FROM sale_payments WHERE sale_id = :saleId AND method = 'CASH'
                        """)
                .param("saleId", saleId)
                .query(BigDecimal.class)
                .single();
        BigDecimal cashTendered = jdbc.sql("""
                        SELECT tendered_amount FROM sale_payments WHERE sale_id = :saleId AND method = 'CASH'
                        """)
                .param("saleId", saleId)
                .query(BigDecimal.class)
                .single();
        assertThat(cashAmount).isEqualByComparingTo("1990");
        assertThat(cashTendered).isEqualByComparingTo("5000");

        // Verificar captura de autorización de tarjeta
        String authStatus = jdbc.sql("SELECT status FROM card_authorizations WHERE id = :id")
                .param("id", cardAuthId).query(String.class).single();
        Long authSaleId = jdbc.sql("SELECT sale_id FROM card_authorizations WHERE id = :id")
                .param("id", cardAuthId).query(Long.class).single();
        assertThat(authStatus).isEqualTo("CAPTURED");
        assertThat(authSaleId).isEqualTo(saleId);

        // Verificar descuento de stock y kardex SALE
        BigDecimal stockAfter = jdbc.sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", productId).query(BigDecimal.class).single();
        assertThat(stockAfter).isEqualByComparingTo("9.000");

        BigDecimal movementDelta = jdbc.sql("""
                        SELECT quantity_delta FROM stock_movements
                        WHERE store_id = :s AND source_type = 'SALE' AND source_id = :saleId
                        """)
                .param("s", storeId).param("saleId", saleId).query(BigDecimal.class).single();
        assertThat(movementDelta).isEqualByComparingTo("-1.000");

        // Verificar SaleLookupApi y CashFlowContributor
        assertThat(saleLookupApi.cashierOf(saleId)).contains(cashierId);

        Map<PaymentMethod, BigDecimal> aggregated = cashFlowContributors.stream()
                .map(c -> c.netAmountsBySession(cashSessionId))
                .reduce(new java.util.EnumMap<>(PaymentMethod.class), (acc, m) -> {
                    m.forEach((k, v) -> acc.merge(k, v, BigDecimal::add));
                    return acc;
                });
        assertThat(aggregated.get(PaymentMethod.DEBIT)).isEqualByComparingTo("4000");
        assertThat(aggregated.get(PaymentMethod.CASH)).isEqualByComparingTo("1990");
    }

    @Test
    void flow2DiscountOverLimitRequiresOverrideAndAuditsAuthorizationId() throws Exception {
        long productId = createProduct("VTA2-" + suffix, "Aceite 1L " + suffix,
                "UNIT", "UNIT", 2000, new BigDecimal("1200.0000"), null, null);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("1200.0000"));

        // Cajero pide 12 % de descuento (su límite es 5 %, el del supervisor 15 %)
        String payload = """
                {
                  "lines": [
                    {
                      "productId": %d,
                      "quantity": 1,
                      "manualDiscount": { "kind": "PERCENT", "value": 12, "reason": "Envase golpeado" }
                    }
                  ],
                  "payments": [
                    { "method": "CASH", "tenderedAmount": 2000 }
                  ]
                }
                """.formatted(productId);

        MvcResult requiredRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.action").value("DISCOUNT_ITEM_OVER_LIMIT"))
                .andExpect(jsonPath("$.context.cartHash").isString())
                .andReturn();

        JsonNode errBody = jsonMapper.readTree(requiredRes.getResponse().getContentAsString());
        JsonNode overrideContext = errBody.get("context");

        String overrideReq = jsonMapper.writeValueAsString(Map.of(
                "action", "DISCOUNT_ITEM_OVER_LIMIT",
                "context", overrideContext,
                "supervisorUsername", supervisorUsername,
                "method", "PIN",
                "secret", "1234",
                "reason", "Autorizado por envase"
        ));

        MvcResult overrideRes = mvc.perform(post("/api/v1/auth/overrides")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overrideReq)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode overrideBody = jsonMapper.readTree(overrideRes.getResponse().getContentAsString());
        String overrideToken = overrideBody.get("overrideToken").asText();
        long authorizationId = overrideBody.get("authorizationId").asLong();

        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Override-Token", overrideToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountTotal").value(240))
                .andExpect(jsonPath("$.totalPayable").value(1760))
                .andReturn();

        long saleId = jsonMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asLong();

        Long discountAuthId = jdbc.sql("""
                        SELECT authorization_id FROM sale_discounts
                        WHERE sale_id = :saleId AND source = 'MANUAL'
                        """)
                .param("saleId", saleId).query(Long.class).single();
        assertThat(discountAuthId).isEqualTo(authorizationId);

        Long auditCount = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'SALE_DISCOUNT_OVER_LIMIT'
                          AND entity_type = 'sale'
                          AND entity_id = :entityId
                          AND authorization_id = :authId
                        """)
                .param("entityId", String.valueOf(saleId))
                .param("authId", authorizationId)
                .query(Long.class).single();
        assertThat(auditCount).isEqualTo(1L);
    }

    @Test
    void flow3RestrictedProductRequiresAgeConfirmationAndAudits() throws Exception {
        long alcoholId = createProduct("VTA3-" + suffix, "Cerveza Artesanal " + suffix,
                "UNIT", "UNIT", 1500, new BigDecimal("800.0000"), "ALCOHOL", null);
        addInitialStock(alcoholId, new BigDecimal("10.000"), new BigDecimal("800.0000"));

        mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 2000 }],
                                  "ageConfirmed": false
                                }
                                """.formatted(alcoholId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AGE_CONFIRMATION_REQUIRED"));

        MvcResult confirmedRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 2000 }],
                                  "ageConfirmed": true
                                }
                                """.formatted(alcoholId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ageConfirmed").value(true))
                .andReturn();

        long saleId = jsonMapper.readTree(confirmedRes.getResponse().getContentAsString()).get("id").asLong();
        Boolean dbAgeConfirmed = jdbc.sql("SELECT age_confirmed FROM sales WHERE id = :id")
                .param("id", saleId).query(Boolean.class).single();
        assertThat(dbAgeConfirmed).isTrue();

        Long auditCount = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'SALE_RESTRICTED_AGE_CONFIRMED'
                          AND entity_type = 'sale'
                          AND entity_id = :entityId
                        """)
                .param("entityId", String.valueOf(saleId)).query(Long.class).single();
        assertThat(auditCount).isEqualTo(1L);
    }

    @Test
    void packSaleDeductsBaseProductStockAndComputesPackCost() throws Exception {
        long baseId = createProduct("BASE-" + suffix, "Bebida Lata 350ml " + suffix,
                "UNIT", "UNIT", 1000, new BigDecimal("400.0000"), null, null);
        long packId = createProduct("PACK-" + suffix, "Pack 6x Lata 350ml " + suffix,
                "PACK", "UNIT", 5500, new BigDecimal("2400.0000"), null, baseId);
        addInitialStock(baseId, new BigDecimal("20.000"), new BigDecimal("400.0000"));

        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 2 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 11000 }]
                                }
                                """.formatted(packId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalPayable").value(11000))
                .andReturn();

        long saleId = jsonMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asLong();
        BigDecimal baseStockAfter = jdbc.sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", baseId).query(BigDecimal.class).single();
        assertThat(baseStockAfter).isEqualByComparingTo("8.000");

        var lineRow = jdbc.sql("""
                        SELECT stock_product_id, stock_quantity, unit_cost, line_cost
                        FROM sale_lines WHERE sale_id = :saleId
                        """)
                .param("saleId", saleId)
                .query((rs, n) -> new Object[]{
                        rs.getLong("stock_product_id"),
                        rs.getBigDecimal("stock_quantity"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("line_cost")
                })
                .single();
        assertThat(lineRow[0]).isEqualTo(baseId);
        assertThat((BigDecimal) lineRow[1]).isEqualByComparingTo("12.000");
        assertThat((BigDecimal) lineRow[2]).isEqualByComparingTo("2400.0000");
        assertThat((BigDecimal) lineRow[3]).isEqualByComparingTo("4800.00");
    }

    @Test
    void customerCreditRejectedInPhase1AndPaymentMismatchReturns422() throws Exception {
        long productId = createProduct("VTA4-" + suffix, "Arroz 1kg " + suffix,
                "UNIT", "UNIT", 1500, new BigDecimal("900.0000"), null, null);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("900.0000"));

        // Fase 1 rechaza CUSTOMER_CREDIT con 400 VALIDATION_ERROR
        mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CUSTOMER_CREDIT", "amount": 1500 }]
                                }
                                """.formatted(productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Pago insuficiente -> 422 PAYMENT_MISMATCH
        mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 1000 }]
                                }
                                """.formatted(productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
    }

    @Test
    void idempotencyReplayAndConflictWorkCorrectly() throws Exception {
        long productId = createProduct("VTA5-" + suffix, "Azúcar 1kg " + suffix,
                "UNIT", "UNIT", 1200, new BigDecimal("700.0000"), null, null);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("700.0000"));

        UUID idemKey = UUID.randomUUID();
        String body1 = """
                {
                  "lines": [{ "productId": %d, "quantity": 1 }],
                  "payments": [{ "method": "CASH", "tenderedAmount": 1500 }]
                }
                """.formatted(productId);
        String body2 = """
                {
                  "lines": [{ "productId": %d, "quantity": 2 }],
                  "payments": [{ "method": "CASH", "tenderedAmount": 2500 }]
                }
                """.formatted(productId);

        MvcResult first = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", idemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body1)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult replay = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", idemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body1)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(jsonMapper.readTree(replay.getResponse().getContentAsString()).get("id").asLong())
                .isEqualTo(jsonMapper.readTree(first.getResponse().getContentAsString()).get("id").asLong());

        // Stock se descontó solo una vez
        BigDecimal stockAfter = jdbc.sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", productId).query(BigDecimal.class).single();
        assertThat(stockAfter).isEqualByComparingTo("9.000");

        // Distinto cuerpo con misma clave -> 409 IDEMPOTENCY_CONFLICT
        mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", idemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body2)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void ticketReprintWithOverrideAndScopePEnforcedForCashier() throws Exception {
        long productId = createProduct("VTA6-" + suffix, "Pan Molde " + suffix,
                "UNIT", "UNIT", 1800, new BigDecimal("1000.0000"), null, null);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("1000.0000"));

        // Venta 1 por cajero 1
        MvcResult sale1Res = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 2000 }]
                                }
                                """.formatted(productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sale1 = jsonMapper.readTree(sale1Res.getResponse().getContentAsString());
        long sale1Id = sale1.get("id").asLong();
        String sale1Number = sale1.get("number").asText();

        // Venta 2 por cajero 2 en otra terminal
        MvcResult sale2Res = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", otherTerminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 2000 }]
                                }
                                """.formatted(productId))
                        .with(cashierAuth(otherCashierId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sale2 = jsonMapper.readTree(sale2Res.getResponse().getContentAsString());
        long sale2Id = sale2.get("id").asLong();
        String sale2Number = sale2.get("number").asText();

        // Ticket inicial libre
        mvc.perform(get("/api/v1/sales/{id}/ticket", sale1Id)
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(sale1Number))
                .andExpect(jsonPath("$.reprintCount").value(0))
                .andExpect(jsonPath("$.copyLabel").doesNotExist());

        // Cajero intenta reimprimir sin override -> 422 OVERRIDE_REQUIRED
        MvcResult reprintReq = mvc.perform(post("/api/v1/sales/{id}/reprint", sale1Id)
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.action").value("RECEIPT_REPRINT"))
                .andReturn();

        JsonNode reprintCtx = jsonMapper.readTree(reprintReq.getResponse().getContentAsString()).get("context");
        String overrideBody = jsonMapper.writeValueAsString(Map.of(
                "action", "RECEIPT_REPRINT",
                "context", reprintCtx,
                "supervisorUsername", supervisorUsername,
                "method", "PIN",
                "secret", "1234"
        ));
        MvcResult overrideRes = mvc.perform(post("/api/v1/auth/overrides")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overrideBody)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode overrideJson = jsonMapper.readTree(overrideRes.getResponse().getContentAsString());
        String reprintToken = overrideJson.get("overrideToken").asText();
        long reprintAuthId = overrideJson.get("authorizationId").asLong();

        // Reimpresión con token -> 200 COPIA y auditoría RECEIPT_REPRINTED
        mvc.perform(post("/api/v1/sales/{id}/reprint", sale1Id)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("X-Override-Token", reprintToken)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copyLabel").value("COPIA"))
                .andExpect(jsonPath("$.reprintCount").value(1));

        Long reprintAudit = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'RECEIPT_REPRINTED'
                          AND entity_type = 'sale'
                          AND entity_id = :entityId
                          AND authorization_id = :authId
                        """)
                .param("entityId", String.valueOf(sale1Id))
                .param("authId", reprintAuthId)
                .query(Long.class).single();
        assertThat(reprintAudit).isEqualTo(1L);

        // Alcance P: cajero 1 no ve la venta del cajero 2 (404 en detalle, número y ticket)
        mvc.perform(get("/api/v1/sales/{id}", sale2Id)
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sales/by-number/{number}", sale2Number)
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sales/{id}/ticket", sale2Id)
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isNotFound());

        // Listado GET /sales para cajero 1 incluye venta 1 y excluye venta 2
        MvcResult cashierListRes = mvc.perform(get("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode cashierContent = jsonMapper.readTree(cashierListRes.getResponse().getContentAsString()).get("content");
        assertThat(cashierContent).anyMatch(n -> n.get("id").asLong() == sale1Id);
        assertThat(cashierContent).noneMatch(n -> n.get("id").asLong() == sale2Id);

        // Supervisor ve ambas ventas
        MvcResult supervisorListRes = mvc.perform(get("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode supervisorContent = jsonMapper.readTree(supervisorListRes.getResponse().getContentAsString()).get("content");
        assertThat(supervisorContent).anyMatch(n -> n.get("id").asLong() == sale1Id);
        assertThat(supervisorContent).anyMatch(n -> n.get("id").asLong() == sale2Id);

        mvc.perform(get("/api/v1/sales/{id}", sale2Id)
                        .header("X-Store-Id", storeId)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(sale2Number));

        // Búsqueda filtrada por folio exacto, estado y medio de pago
        mvc.perform(get("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .param("q", sale1Number)
                        .param("status", "COMPLETED")
                        .param("paymentMethod", "CASH")
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(sale1Id));

        // Consulta por número propio del cajero
        mvc.perform(get("/api/v1/sales/by-number/{number}", sale1Number)
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sale1Id));
    }

    @Test
    void multipleCommaSeparatedOverrideTokensAuthorizeItemAndTotalDiscounts() throws Exception {
        long productId = createProduct("VTA7-" + suffix, "Queso Gauda 1kg " + suffix,
                "UNIT", "UNIT", 10000, new BigDecimal("6000.0000"), null, null);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("6000.0000"));

        // Descuento de línea 10% (límite cajero 5%) + descuento al total 8% (límite cajero 3% / $3.000)
        // Subtotal 10.000 -> desc línea 1.000 -> base 9.000 -> desc total 8% = 720 -> total = 8.280
        String cartPayloadNoPayments = """
                {
                  "lines": [
                    {
                      "productId": %d,
                      "quantity": 1,
                      "manualDiscount": { "kind": "PERCENT", "value": 10, "reason": "Promo interna" }
                    }
                  ],
                  "totalDiscount": { "kind": "PERCENT", "value": 8, "reason": "Cliente frecuente" }
                }
                """.formatted(productId);

        MvcResult quoteRes = mvc.perform(post("/api/v1/pos/cart/quote")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cartPayloadNoPayments)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredOverrides.length()").value(2))
                .andReturn();

        JsonNode reqOverrides = jsonMapper.readTree(quoteRes.getResponse().getContentAsString())
                .get("requiredOverrides");

        String token1 = requestOverride(reqOverrides.get(0));
        String token2 = requestOverride(reqOverrides.get(1));

        String salePayload = """
                {
                  "lines": [
                    {
                      "productId": %d,
                      "quantity": 1,
                      "manualDiscount": { "kind": "PERCENT", "value": 10, "reason": "Promo interna" }
                    }
                  ],
                  "totalDiscount": { "kind": "PERCENT", "value": 8, "reason": "Cliente frecuente" },
                  "payments": [
                    { "method": "CASH", "tenderedAmount": 9000 }
                  ]
                }
                """.formatted(productId);

        // Enviar tokens separados por coma en orden inverso para verificar emparejamiento por contexto
        mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Override-Token", token2 + ", " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(salePayload)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountTotal").value(1720))
                .andExpect(jsonPath("$.totalPayable").value(8280))
                .andExpect(jsonPath("$.discounts.length()").value(2));
    }

    private String requestOverride(JsonNode requiredOverride) throws Exception {
        String body = jsonMapper.writeValueAsString(Map.of(
                "action", requiredOverride.get("action").asText(),
                "context", requiredOverride.get("context"),
                "supervisorUsername", supervisorUsername,
                "method", "PIN",
                "secret", "1234"
        ));
        MvcResult res = mvc.perform(post("/api/v1/auth/overrides")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("overrideToken").asText();
    }

    private RequestPostProcessor cashierAuth(long uid) {
        return user(String.valueOf(uid)).authorities(
                new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE),
                new SimpleGrantedAuthority(Permissions.SALES_SALE_READ),
                new SimpleGrantedAuthority(Permissions.SALES_DISCOUNT_APPLY)
        );
    }

    private RequestPostProcessor supervisorAuth(long uid) {
        return user(String.valueOf(uid)).authorities(
                new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE),
                new SimpleGrantedAuthority(Permissions.SALES_SALE_READ),
                new SimpleGrantedAuthority(Permissions.SALES_DISCOUNT_APPLY),
                new SimpleGrantedAuthority(Permissions.SALES_DISCOUNT_OVERRIDE),
                new SimpleGrantedAuthority(Permissions.SALES_SALE_VOID),
                new SimpleGrantedAuthority(Permissions.SALES_RETURN_CREATE),
                new SimpleGrantedAuthority(Permissions.SALES_RECEIPT_REPRINT),
                new SimpleGrantedAuthority(Permissions.REPORT_SALES_READ)
        );
    }

    private long insertUserWithRole(String username, String fullName, String rawPin, String roleCode) {
        String pinHash = rawPin != null ? passwordEncoder.encode(rawPin) : null;
        long uid = jdbc.sql("""
                        INSERT INTO users (username, full_name, password_hash, pin_hash)
                        VALUES (:u, :name, 'x', :pinHash)
                        RETURNING id
                        """)
                .param("u", username)
                .param("name", fullName)
                .param("pinHash", pinHash)
                .query(Long.class)
                .single();

        jdbc.sql("INSERT INTO user_stores (user_id, store_id) VALUES (:u, :s) ON CONFLICT DO NOTHING")
                .param("u", uid)
                .param("s", storeId)
                .update();

        long roleId = jdbc.sql("SELECT id FROM roles WHERE code = :code")
                .param("code", roleCode)
                .query(Long.class)
                .single();

        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r) ON CONFLICT DO NOTHING")
                .param("u", uid)
                .param("r", roleId)
                .update();

        return uid;
    }

    private long insertTerminal(long store, String code) {
        return jdbc.sql("""
                        INSERT INTO terminals (store_id, code, name, active)
                        VALUES (:s, :c, :n, true)
                        RETURNING id
                        """)
                .param("s", store)
                .param("c", code)
                .param("n", "Caja " + code)
                .query(Long.class)
                .single();
    }

    private long openCashSession(long store, long terminal, long cashier) {
        String num = "TU-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return jdbc.sql("""
                        INSERT INTO cash_sessions (number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount)
                        VALUES (:num, :s, :t, :c, 'OPEN', CURRENT_DATE, 50000)
                        RETURNING id
                        """)
                .param("num", num)
                .param("s", store)
                .param("t", terminal)
                .param("c", cashier)
                .query(Long.class)
                .single();
    }

    private long createCategory(String name) {
        return jdbc.sql("""
                        INSERT INTO categories (name, active) VALUES (:name, true) RETURNING id
                        """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long createProduct(
            String sku,
            String name,
            String saleType,
            String unitOfMeasure,
            long priceGross,
            BigDecimal avgCost,
            String restrictedType,
            Long baseProductId
    ) {
        long productId = jdbc.sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, restricted_type,
                            lot_controlled, active, allow_manual_discount
                        )
                        VALUES (:sku, :name, :cat, :st, :uom, :price, :cost, false, :rest, false, true, true)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("cat", categoryId)
                .param("st", saleType)
                .param("uom", unitOfMeasure)
                .param("price", priceGross)
                .param("cost", avgCost != null ? avgCost : BigDecimal.ZERO)
                .param("rest", restrictedType)
                .query(Long.class)
                .single();

        long ivaId = jdbc.sql("SELECT id FROM taxes WHERE code = 'IVA'").query(Long.class).single();
        jdbc.sql("INSERT INTO product_taxes (product_id, tax_id) VALUES (:p, :t)")
                .param("p", productId)
                .param("t", ivaId)
                .update();

        if ("PACK".equals(saleType) && baseProductId != null) {
            jdbc.sql("""
                            INSERT INTO product_packs (product_id, base_product_id, factor)
                            VALUES (:p, :b, 6)
                            """)
                    .param("p", productId)
                    .param("b", baseProductId)
                    .update();
        }
        return productId;
    }

    private void addInitialStock(long productId, BigDecimal qty, BigDecimal unitCost) {
        jdbc.sql("""
                        INSERT INTO stock (store_id, product_id, quantity)
                        VALUES (:storeId, :productId, 0)
                        ON CONFLICT (store_id, product_id) DO NOTHING
                        """)
                .param("storeId", storeId)
                .param("productId", productId)
                .update();

        tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                storeId,
                StockMovementType.INITIAL,
                StockSourceType.INITIAL_LOAD,
                productId,
                cashierId,
                "Carga inicial test T36",
                List.of(StockLine.of(productId, qty, unitCost, null))
        )));
    }
}
