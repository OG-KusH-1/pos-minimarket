package cl.minimarket.pos.sales.web.returns;

import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.awaitility.Awaitility;
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
import java.time.Duration;
import java.time.LocalDate;
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
 * Pruebas de integración de anulación y devoluciones de ventas ({@code PLAN.md} H.3 T71,
 * {@code API.md} F.4 flujo 4, RN-12, RN-13, RN-14).
 */
class SaleReturnControllerIT extends AbstractIntegrationIT {

    @Autowired
    cl.minimarket.pos.customers.CustomerCreditApi customerCreditApi;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    StockApi stockApi;

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
    private long managerId;
    private String managerUsername;
    private long terminalId;
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
        managerUsername = "mgr_" + suffix;
        managerId = insertUserWithRole(managerUsername, "Jefe Local Prueba", "5678", "MANAGER");

        terminalId = insertTerminal(storeId, "CAJA-R1-" + suffix);
        cashSessionId = openCashSession(storeId, terminalId, cashierId);
        categoryId = createCategory("Cat Dev " + suffix);
    }

    @Test
    void flow4PartialReturnWithOverrideRestockLotReentryAndCreditNote61() throws Exception {
        long productId = createProduct("DEV1-" + suffix, "Leche Entera 1L " + suffix, 1500, true);
        addInitialStockWithLot(productId, new BigDecimal("10.000"), new BigDecimal("900.0000"), "LOT-" + suffix);

        Long lotId = jdbc.sql("SELECT id FROM lots WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", productId).query(Long.class).single();

        JsonNode sale = createCashSale(cashierId, productId, 3, 5000);
        long saleId = sale.get("id").asLong();
        long saleLineId = sale.get("lines").get(0).get("id").asLong();

        mvc.perform(get("/api/v1/sales/{id}/returnable", saleId)
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleId").value(saleId))
                .andExpect(jsonPath("$.voidAllowed").value(true))
                .andExpect(jsonPath("$.lines[0].returnableQuantity").value(3));

        UUID idempKey = UUID.randomUUID();
        String returnPayload = """
                {
                  "reasonCode": "CUSTOMER_REQUEST",
                  "reasonText": "Cliente devuelve 1 unidad",
                  "lines": [
                    { "saleLineId": %d, "quantity": 1, "restock": true }
                  ]
                }
                """.formatted(saleLineId);

        // Sin permiso sales:return:create -> 422 OVERRIDE_REQUIRED
        MvcResult overrideReqRes = mvc.perform(post("/api/v1/sales/{id}/returns", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", idempKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnPayload)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.action").value("SALE_RETURN"))
                .andReturn();

        JsonNode overrideContext = jsonMapper.readTree(overrideReqRes.getResponse().getContentAsString())
                .get("context");

        String overrideToken = requestOverrideToken(
                cashierId, supervisorUsername, "1234", "SALE_RETURN", overrideContext);

        // Reintento con el mismo Idempotency-Key y X-Override-Token
        MvcResult returnRes = mvc.perform(post("/api/v1/sales/{id}/returns", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", idempKey.toString())
                        .header("X-Override-Token", overrideToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnPayload)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saleId").value(saleId))
                .andExpect(jsonPath("$.returnType").value("RETURN"))
                .andExpect(jsonPath("$.refundTotal").value(1500))
                .andExpect(jsonPath("$.lines[0].quantity").value(1))
                .andExpect(jsonPath("$.lines[0].restock").value(true))
                .andReturn();

        long saleReturnId = jsonMapper.readTree(returnRes.getResponse().getContentAsString()).get("id").asLong();

        // Verificar estado de la venta, stock y lote original
        String saleStatus = jdbc.sql("SELECT status FROM sales WHERE id = :id")
                .param("id", saleId).query(String.class).single();
        assertThat(saleStatus).isEqualTo("PARTIALLY_RETURNED");

        BigDecimal stockAfter = jdbc.sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", productId).query(BigDecimal.class).single();
        assertThat(stockAfter).isEqualByComparingTo("8.000");

        BigDecimal lotQtyAfter = jdbc.sql("SELECT quantity FROM lots WHERE id = :id")
                .param("id", lotId).query(BigDecimal.class).single();
        assertThat(lotQtyAfter).isEqualByComparingTo("8.000");

        // Auditoría SALE_RETURNED
        Integer auditCount = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'SALE_RETURNED' AND entity_type = 'sale_return' AND entity_id = :id
                        """)
                .param("id", String.valueOf(saleReturnId))
                .query(Integer.class).single();
        assertThat(auditCount).isEqualTo(1);

        // Evento SaleReturnedEvent -> billing emite Nota de Crédito 61
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer ncCount = jdbc.sql("""
                            SELECT COUNT(*) FROM tax_documents
                            WHERE sale_return_id = :id AND sii_type_code = 61
                            """)
                    .param("id", saleReturnId)
                    .query(Integer.class).single();
            assertThat(ncCount).isEqualTo(1);
        });
    }

    @Test
    void voidSaleWithOpenSessionReversesCardAndCreditAndRestocks() throws Exception {
        long productId = createProduct("VOID1-" + suffix, "Aceite 1L " + suffix, 3000, false);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("1800.0000"));
        long customerId = insertCreditCustomer("Cliente Fiado " + suffix, new BigDecimal("50000"));

        // Autorizar tarjeta por 2000
        MvcResult cardRes = mvc.perform(post("/api/v1/pos/card-payments")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "DEBIT", "amount": 2000 }
                                """)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andReturn();
        long cardAuthId = jsonMapper.readTree(cardRes.getResponse().getContentAsString()).get("id").asLong();

        // Venta 2 unidades = 6000: 2000 DEBIT + 4000 CASH (POST /sales aún no habilita CUSTOMER_CREDIT en Fase 1);
        // luego ajustamos el pago CASH a CUSTOMER_CREDIT + cargo en CustomerCreditApi para probar la reversa de fiado.
        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": %d,
                                  "lines": [
                                    { "productId": %d, "quantity": 2 }
                                  ],
                                  "payments": [
                                    { "method": "DEBIT", "amount": 2000, "cardAuthorizationId": %d },
                                    { "method": "CASH", "tenderedAmount": 4000 }
                                  ]
                                }
                                """.formatted(customerId, productId, cardAuthId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andReturn();
        long saleId = jsonMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asLong();
        awaitBoletaIssued(saleId);

        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            UPDATE sale_payments
                            SET method = 'CUSTOMER_CREDIT', tendered_amount = NULL, change_amount = 0
                            WHERE sale_id = :saleId AND method = 'CASH'
                            """)
                    .param("saleId", saleId)
                    .update();
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            String.valueOf(cashierId), "n/a",
                            List.of(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))
                    )
            );
            try (var ignored = cl.minimarket.pos.shared.context.StoreContext.bind(
                    new cl.minimarket.pos.shared.context.StoreContext(storeId, terminalId))) {
                customerCreditApi.charge(customerId, new BigDecimal("4000"), saleId, storeId);
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        });

        BigDecimal debtBeforeVoid = jdbc.sql("SELECT credit_balance FROM customers WHERE id = :id")
                .param("id", customerId).query(BigDecimal.class).single();
        assertThat(debtBeforeVoid).isEqualByComparingTo("4000");

        UUID voidKey = UUID.randomUUID();
        MvcResult voidRes = mvc.perform(post("/api/v1/sales/{id}/void", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", voidKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reasonCode": "CASHIER_ERROR", "reasonText": "Cobro duplicado" }
                                """)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.returnType").value("VOID"))
                .andExpect(jsonPath("$.refundTotal").value(6000))
                .andReturn();

        long saleReturnId = jsonMapper.readTree(voidRes.getResponse().getContentAsString()).get("id").asLong();

        // Reintento idempotente con la misma clave devuelve el mismo recurso
        mvc.perform(post("/api/v1/sales/{id}/void", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", voidKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reasonCode": "CASHIER_ERROR", "reasonText": "Cobro duplicado" }
                                """)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(saleReturnId));

        // Segundo intento con distinta clave -> 409 INVALID_STATE_TRANSITION
        mvc.perform(post("/api/v1/sales/{id}/void", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reasonCode": "CASHIER_ERROR" }
                                """)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // Verificar deuda del cliente reversada, stock reingresado y pagos REVERSED
        BigDecimal debtAfterVoid = jdbc.sql("SELECT credit_balance FROM customers WHERE id = :id")
                .param("id", customerId).query(BigDecimal.class).single();
        assertThat(debtAfterVoid).isEqualByComparingTo("0");

        BigDecimal stockAfterVoid = jdbc.sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", productId).query(BigDecimal.class).single();
        assertThat(stockAfterVoid).isEqualByComparingTo("10.000");

        Integer reversedPayments = jdbc.sql("""
                        SELECT COUNT(*) FROM sale_payments WHERE sale_id = :id AND status = 'REVERSED'
                        """)
                .param("id", saleId).query(Integer.class).single();
        assertThat(reversedPayments).isEqualTo(2);

        Integer voidAudit = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'SALE_VOIDED' AND entity_type = 'sale' AND entity_id = :id
                        """)
                .param("id", String.valueOf(saleId)).query(Integer.class).single();
        assertThat(voidAudit).isEqualTo(1);
    }

    @Test
    void voidSaleRn12RejectedWhenOriginalSessionClosedOrAlreadyReturned() throws Exception {
        long productId = createProduct("RN12-" + suffix, "Arroz 1kg " + suffix, 1200, false);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("700.0000"));

        JsonNode sale1 = createCashSale(cashierId, productId, 2, 5000);
        long sale1Id = sale1.get("id").asLong();
        long sale1LineId = sale1.get("lines").get(0).get("id").asLong();

        // Devolución parcial de sale1 -> luego intentar anular -> 422 VOID_NOT_ALLOWED
        mvc.perform(post("/api/v1/sales/{id}/returns", sale1Id)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reasonCode": "DEFECTIVE",
                                  "lines": [{ "saleLineId": %d, "quantity": 1, "restock": true }]
                                }
                                """.formatted(sale1LineId))
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/sales/{id}/void", sale1Id)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reasonCode": "CASHIER_ERROR" }
                                """)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VOID_NOT_ALLOWED"));

        // Venta 2 en el turno actual; luego cerramos el turno original y abrimos uno nuevo -> VOID_NOT_ALLOWED
        JsonNode sale2 = createCashSale(cashierId, productId, 1, 2000);
        long sale2Id = sale2.get("id").asLong();

        jdbc.sql("UPDATE cash_sessions SET status = 'CLOSED', closed_at = now() WHERE id = :id")
                .param("id", cashSessionId).update();
        openCashSession(storeId, terminalId, cashierId);

        mvc.perform(post("/api/v1/sales/{id}/void", sale2Id)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reasonCode": "CASHIER_ERROR" }
                                """)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VOID_NOT_ALLOWED"));
    }

    @Test
    void returnQuantityExceededRn13AndReturnWindowExpired() throws Exception {
        long productId = createProduct("RN13-" + suffix, "Azúcar 1kg " + suffix, 1100, false);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("650.0000"));

        JsonNode sale = createCashSale(cashierId, productId, 2, 5000);
        long saleId = sale.get("id").asLong();
        long saleLineId = sale.get("lines").get(0).get("id").asLong();

        // Pedir devolver 3 de 2 -> 422 RETURN_QUANTITY_EXCEEDED
        mvc.perform(post("/api/v1/sales/{id}/returns", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reasonCode": "CUSTOMER_REQUEST",
                                  "lines": [{ "saleLineId": %d, "quantity": 3, "restock": true }]
                                }
                                """.formatted(saleLineId))
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDED"));

        // Venta con 31 días de antigüedad (límite 30) -> 422 RETURN_WINDOW_EXPIRED
        jdbc.sql("UPDATE sales SET business_date = :oldDate WHERE id = :id")
                .param("oldDate", LocalDate.now().minusDays(31))
                .param("id", saleId)
                .update();

        mvc.perform(post("/api/v1/sales/{id}/returns", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reasonCode": "CUSTOMER_REQUEST",
                                  "lines": [{ "saleLineId": %d, "quantity": 1, "restock": true }]
                                }
                                """.formatted(saleLineId))
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_WINDOW_EXPIRED"));
    }

    @Test
    void returnWithoutRestockCreatesPendingShrinkageWithoutStockMovement() throws Exception {
        long productId = createProduct("MERMA-" + suffix, "Yogurt 150g " + suffix, 600, false);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("350.0000"));

        JsonNode sale = createCashSale(cashierId, productId, 2, 2000);
        long saleId = sale.get("id").asLong();
        long saleLineId = sale.get("lines").get(0).get("id").asLong();

        MvcResult res = mvc.perform(post("/api/v1/sales/{id}/returns", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reasonCode": "DEFECTIVE",
                                  "reasonText": "Envase roto",
                                  "lines": [{ "saleLineId": %d, "quantity": 1, "restock": false }]
                                }
                                """.formatted(saleLineId))
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].restock").value(false))
                .andReturn();

        long saleReturnId = jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
        long returnLineId = jdbc.sql("SELECT id FROM sale_return_lines WHERE sale_return_id = :id")
                .param("id", saleReturnId).query(Long.class).single();

        // Stock sigue en 8.000 (10 - 2), sin movimiento SALE_RETURN
        BigDecimal stock = jdbc.sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", productId).query(BigDecimal.class).single();
        assertThat(stock).isEqualByComparingTo("8.000");

        Integer returnMovements = jdbc.sql("""
                        SELECT COUNT(*) FROM stock_movements
                        WHERE source_type = 'SALE_RETURN' AND source_id = :id
                        """)
                .param("id", saleReturnId).query(Integer.class).single();
        assertThat(returnMovements).isZero();

        // Merma sistémica creada en PENDING_APPROVAL con affects_stock = false
        Map<String, Object> shrinkage = jdbc.sql("""
                        SELECT shrinkage_type, status, affects_stock, quantity
                        FROM shrinkages
                        WHERE sale_return_line_id = :lineId
                        """)
                .param("lineId", returnLineId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "type", rs.getString("shrinkage_type"),
                        "status", rs.getString("status"),
                        "affectsStock", rs.getBoolean("affects_stock"),
                        "quantity", rs.getBigDecimal("quantity")
                ))
                .single();
        assertThat(shrinkage.get("type")).isEqualTo("DAMAGED");
        assertThat(shrinkage.get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(shrinkage.get("affectsStock")).isEqualTo(false);
        assertThat((BigDecimal) shrinkage.get("quantity")).isEqualByComparingTo("1.000");
    }

    @Test
    void returnOverSupervisorLimitRequiresOverrideAndValidatesPaymentMismatch() throws Exception {
        long productId = createProduct("LIM-" + suffix, "Pack Premium " + suffix, 60000, false);
        addInitialStock(productId, new BigDecimal("5.000"), new BigDecimal("35000.0000"));

        JsonNode sale = createCashSale(cashierId, productId, 1, 60000);
        long saleId = sale.get("id").asLong();
        long saleLineId = sale.get("lines").get(0).get("id").asLong();

        // Reembolso con suma incorrecta -> 422 PAYMENT_MISMATCH (con manager que no tiene límite)
        mvc.perform(post("/api/v1/sales/{id}/returns", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reasonCode": "DEFECTIVE",
                                  "lines": [{ "saleLineId": %d, "quantity": 1, "restock": true }],
                                  "refunds": [{ "method": "CASH", "amount": 50000 }]
                                }
                                """.formatted(saleLineId))
                        .with(managerAuth(managerId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));

        // Supervisor tiene RETURN_MAX_AMOUNT = 50000, la devolución de 60000 requiere override
        String payload = """
                {
                  "reasonCode": "DEFECTIVE",
                  "lines": [{ "saleLineId": %d, "quantity": 1, "restock": true }]
                }
                """.formatted(saleLineId);

        MvcResult overrideReq = mvc.perform(post("/api/v1/sales/{id}/returns", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andReturn();

        JsonNode ctx = jsonMapper.readTree(overrideReq.getResponse().getContentAsString())
                .get("context");
        String managerToken = requestOverrideToken(
                supervisorId, managerUsername, "5678", "SALE_RETURN", ctx);

        mvc.perform(post("/api/v1/sales/{id}/returns", saleId)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Override-Token", managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundTotal").value(60000));
    }

    @Test
    void listAndGetSaleReturnsRespectScopePAndSortParser() throws Exception {
        long productId = createProduct("SCP-" + suffix, "Galletas " + suffix, 1000, false);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("500.0000"));

        JsonNode sale1 = createCashSale(cashierId, productId, 1, 1000);
        long sale1Id = sale1.get("id").asLong();
        JsonNode sale2 = createCashSale(otherCashierId, productId, 1, 1000);
        long sale2Id = sale2.get("id").asLong();

        MvcResult r1 = mvc.perform(post("/api/v1/sales/{id}/void", sale1Id)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reasonCode": "OTHER" }
                                """)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isCreated())
                .andReturn();
        long return1Id = jsonMapper.readTree(r1.getResponse().getContentAsString()).get("id").asLong();

        MvcResult r2 = mvc.perform(post("/api/v1/sales/{id}/void", sale2Id)
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reasonCode": "OTHER" }
                                """)
                        .with(supervisorAuth(supervisorId)))
                .andExpect(status().isCreated())
                .andReturn();
        long return2Id = jsonMapper.readTree(r2.getResponse().getContentAsString()).get("id").asLong();

        // Cajero 1 ve solo su devolución en GET /sale-returns
        mvc.perform(get("/api/v1/sale-returns")
                        .param("sort", "createdAt,desc")
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(return1Id));

        // Cajero 1 recibe 404 al consultar devolución o returnable ajena
        mvc.perform(get("/api/v1/sale-returns/{id}", return2Id)
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/sales/{id}/returnable", sale2Id)
                        .header("X-Store-Id", storeId)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isNotFound());
    }

    private JsonNode createCashSale(long userId, long productId, int qty, long tenderedAmount) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": %d }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": %d }]
                                }
                                """.formatted(productId, qty, tenderedAmount))
                        .with(cashierAuth(userId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sale = jsonMapper.readTree(res.getResponse().getContentAsString());
        awaitBoletaIssued(sale.get("id").asLong());
        return sale;
    }

    private void awaitBoletaIssued(long saleId) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer count = jdbc.sql("SELECT COUNT(*) FROM tax_documents WHERE sale_id = :id")
                    .param("id", saleId)
                    .query(Integer.class)
                    .single();
            assertThat(count).isEqualTo(1);
        });
    }

    private String requestOverrideToken(
            long requesterId,
            String supUsername,
            String pin,
            String action,
            JsonNode context
    ) throws Exception {
        String body = jsonMapper.writeValueAsString(Map.of(
                "action", action,
                "method", "PIN",
                "supervisorUsername", supUsername,
                "secret", pin,
                "context", context
        ));
        MvcResult res = mvc.perform(post("/api/v1/auth/overrides")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(cashierAuth(requesterId)))
                .andExpect(status().isOk())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("overrideToken").asString();
    }

    private RequestPostProcessor cashierAuth(long userId) {
        return user(String.valueOf(userId)).authorities(
                new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE),
                new SimpleGrantedAuthority(Permissions.SALES_SALE_READ)
        );
    }

    private RequestPostProcessor supervisorAuth(long userId) {
        return user(String.valueOf(userId)).authorities(
                new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE),
                new SimpleGrantedAuthority(Permissions.SALES_SALE_READ),
                new SimpleGrantedAuthority(Permissions.SALES_SALE_VOID),
                new SimpleGrantedAuthority(Permissions.SALES_RETURN_CREATE)
        );
    }

    private RequestPostProcessor managerAuth(long userId) {
        return supervisorAuth(userId);
    }

    private long insertUserWithRole(String username, String fullName, String pin, String roleCode) {
        String hash = passwordEncoder.encode("Secret123!");
        String pinHash = pin != null ? passwordEncoder.encode(pin) : null;
        long uid = jdbc.sql("""
                        INSERT INTO users (username, full_name, password_hash, pin_hash)
                        VALUES (:u, :n, :ph, :pin)
                        RETURNING id
                        """)
                .param("u", username)
                .param("n", fullName)
                .param("ph", hash)
                .param("pin", pinHash)
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
        return jdbc.sql("INSERT INTO categories (name, active) VALUES (:name, true) RETURNING id")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long createProduct(String sku, String name, long priceGross, boolean lotControlled) {
        long productId = jdbc.sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, lot_controlled, active, allow_manual_discount
                        )
                        VALUES (:sku, :name, :cat, 'UNIT', 'UNIT', :price, 0, false, :lot, true, true)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("cat", categoryId)
                .param("price", priceGross)
                .param("lot", lotControlled)
                .query(Long.class)
                .single();

        long ivaId = jdbc.sql("SELECT id FROM taxes WHERE code = 'IVA'").query(Long.class).single();
        jdbc.sql("INSERT INTO product_taxes (product_id, tax_id) VALUES (:p, :t)")
                .param("p", productId)
                .param("t", ivaId)
                .update();
        return productId;
    }

    private long insertCreditCustomer(String name, BigDecimal limit) {
        int num = 10_000_000 + Math.abs(suffix.hashCode() % 10_000_000);
        String rut = formatRutWithDv(num);
        return jdbc.sql("""
                        INSERT INTO customers (rut, name, credit_enabled, credit_limit, credit_balance, active)
                        VALUES (:rut, :name, true, :limit, 0, true)
                        RETURNING id
                        """)
                .param("rut", rut)
                .param("name", name)
                .param("limit", limit)
                .query(Long.class)
                .single();
    }

    private static String formatRutWithDv(int num) {
        int m = 0;
        int s = 1;
        int t = num;
        while (t != 0) {
            s = (s + (t % 10) * (9 - (m++ % 6))) % 11;
            t /= 10;
        }
        String dv = s != 0 ? String.valueOf(s - 1) : "K";
        return num + "-" + dv;
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
                "Carga inicial test T71",
                List.of(StockLine.of(productId, qty, unitCost, null))
        )));
    }

    private void addInitialStockWithLot(long productId, BigDecimal qty, BigDecimal unitCost, String lotNumber) {
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
                "Carga inicial lote test T71",
                List.of(new StockLine(productId, qty, unitCost, null, null, lotNumber, LocalDate.now().plusDays(60)))
        )));
    }
}
