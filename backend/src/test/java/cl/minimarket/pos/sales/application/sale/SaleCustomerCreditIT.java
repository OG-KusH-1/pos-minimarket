package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de la venta a fiado ({@code CUSTOMER_CREDIT}) y del motor real de
 * promociones en {@code POST /sales} ({@code PLAN.md} H.3 T72, HU-POS-16, RN-15).
 *
 * <p>Cubre el criterio de terminado de T72: fiado dentro del cupo, 422
 * {@code OVERRIDE_REQUIRED} con acción {@code CREDIT_OVER_LIMIT} y su consumo con token,
 * {@code CREDIT_NOT_ENABLED}, validaciones de forma (cliente y permiso), y una promoción
 * 3×2 ({@code BUY_X_PAY_Y}) aplicada de verdad en {@code POST /sales} vía el motor de T68.
 */
class SaleCustomerCreditIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    StockApi stockApi;

    @Autowired
    BusinessClock businessClock;

    private String suffix;
    private long storeId;
    private long cashierId;
    private long supervisorId;
    private String supervisorUsername;
    private long terminalId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        cashierId = insertUserWithRole("caj_" + suffix, "Cajero Fiado " + suffix, null, "CASHIER");
        supervisorUsername = "supcr_" + suffix;
        supervisorId = insertSupervisorWithCreditOverride(supervisorUsername);

        terminalId = insertTerminal(storeId, "CR-" + suffix);
        openCashSession(storeId, terminalId, cashierId);
        categoryId = createCategory("Cat Fiado " + suffix);
    }

    @Test
    void creditSaleWithinLimitChargesCustomerAccount() throws Exception {
        long customerId = insertCustomer("Cliente Dentro Cupo " + suffix, true, 50000, 10000);
        long productId = createProduct("CRD1-" + suffix, "Fideos 500g " + suffix, 5000);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("3000.0000"));

        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": %d,
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CUSTOMER_CREDIT", "amount": 5000 }]
                                }
                                """.formatted(customerId, productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalGross").value(5000))
                .andExpect(jsonPath("$.totalPayable").value(5000))
                .andExpect(jsonPath("$.paidTotal").value(5000))
                .andReturn();

        long saleId = jsonMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asLong();

        // El cliente queda con deuda 10000 + 5000 = 15000, dentro del cupo de 50000
        BigDecimal balance = jdbc.sql("SELECT credit_balance FROM customers WHERE id = :id")
                .param("id", customerId).query(BigDecimal.class).single();
        assertThat(balance).isEqualByComparingTo("15000");

        // La fila de pago con fiado y la asociación de cliente quedaron registradas
        Long customerIdInSale = jdbc.sql("SELECT customer_id FROM sales WHERE id = :id")
                .param("id", saleId).query(Long.class).single();
        assertThat(customerIdInSale).isEqualTo(customerId);

        BigDecimal paymentAmount = jdbc.sql("""
                        SELECT amount FROM sale_payments
                        WHERE sale_id = :saleId AND method = 'CUSTOMER_CREDIT'
                        """)
                .param("saleId", saleId).query(BigDecimal.class).single();
        assertThat(paymentAmount).isEqualByComparingTo("5000");

        // Movimiento de cuenta corriente tipo CHARGE por el cargo de la venta
        Long movements = jdbc.sql("""
                        SELECT COUNT(*) FROM customer_account_movements
                        WHERE customer_id = :cid AND movement_type = 'CHARGE' AND sale_id = :saleId
                        """)
                .param("cid", customerId).param("saleId", saleId).query(Long.class).single();
        assertThat(movements).isEqualTo(1L);
    }

    @Test
    void creditSaleOverLimitRequiresOverrideAndChargesWithAuthorization() throws Exception {
        // HU-POS-16: cupo 50000, deuda 45000, fiado 10000 → excede el cupo
        long customerId = insertCustomer("Cliente Sobre Cupo " + suffix, true, 50000, 45000);
        long productId = createProduct("CRD2-" + suffix, "Aceite 1L " + suffix, 10000);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("6000.0000"));

        UUID idemKey = UUID.randomUUID();
        String payload = """
                {
                  "customerId": %d,
                  "lines": [{ "productId": %d, "quantity": 1 }],
                  "payments": [{ "method": "CUSTOMER_CREDIT", "amount": 10000 }]
                }
                """.formatted(customerId, productId);

        // Sin token → 422 OVERRIDE_REQUIRED con la acción y el contexto del cupo
        MvcResult requiredRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", idemKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.action").value("CREDIT_OVER_LIMIT"))
                .andExpect(jsonPath("$.context.customerId").value(customerId))
                .andExpect(jsonPath("$.context.amount").value(10000))
                .andExpect(jsonPath("$.context.creditLimit").value(50000))
                .andExpect(jsonPath("$.context.creditBalance").value(45000))
                .andReturn();

        JsonNode context = jsonMapper.readTree(requiredRes.getResponse().getContentAsString()).get("context");
        String overrideToken = requestCreditOverride(context);

        // Reintento con el mismo Idempotency-Key (L-04: el 422 libera la clave) y el token
        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", idemKey.toString())
                        .header("X-Override-Token", overrideToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();

        long saleId = jsonMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asLong();

        // La deuda supera el cupo porque la venta fue autorizada por el supervisor (RN-15)
        BigDecimal balance = jdbc.sql("SELECT credit_balance FROM customers WHERE id = :id")
                .param("id", customerId).query(BigDecimal.class).single();
        assertThat(balance).isEqualByComparingTo("55000");

        // La autorización de supervisor del cargo quedó registrada en la auditoría
        Long authorizationId = jdbc.sql("""
                        SELECT authorization_id FROM audit_log
                        WHERE action = 'CREDIT_OVER_LIMIT' AND entity_type = 'customer'
                          AND entity_id = :eid AND authorization_id IS NOT NULL
                        """)
                .param("eid", String.valueOf(customerId))
                .query(Long.class)
                .single();

        Long audits = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'CREDIT_OVER_LIMIT' AND entity_type = 'customer'
                          AND entity_id = :eid AND authorization_id = :authId
                        """)
                .param("eid", String.valueOf(customerId))
                .param("authId", authorizationId)
                .query(Long.class).single();
        assertThat(audits).isEqualTo(1L);

        // El stock de la venta autorizada se descontó normalmente
        BigDecimal stockAfter = jdbc.sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", productId).query(BigDecimal.class).single();
        assertThat(stockAfter).isEqualByComparingTo("9.000");
    }

    @Test
    void creditSaleWithoutCreditEnabledIsRejected() throws Exception {
        long customerId = insertCustomer("Cliente Sin Fiado " + suffix, false, 0, 0);
        long productId = createProduct("CRD3-" + suffix, "Arroz 1kg " + suffix, 1500);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("900.0000"));

        mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": %d,
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CUSTOMER_CREDIT", "amount": 1500 }]
                                }
                                """.formatted(customerId, productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_NOT_ENABLED"));

        // No se creó ninguna venta ni movimiento de cuenta para el cliente
        Long sales = jdbc.sql("SELECT COUNT(*) FROM sales WHERE customer_id = :cid")
                .param("cid", customerId).query(Long.class).single();
        assertThat(sales).isZero();
    }

    @Test
    void creditSaleRequiresCustomerIdAndCreditSellPermission() throws Exception {
        long productId = createProduct("CRD4-" + suffix, "Azúcar 1kg " + suffix, 1200);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("700.0000"));

        // Fiado sin cliente → 400 VALIDATION_ERROR (F.3: customerId requerido)
        mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CUSTOMER_CREDIT", "amount": 1200 }]
                                }
                                """.formatted(productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Cajero sin customers:credit:sell → 403 ACCESS_DENIED
        long productId2 = createProduct("CRD5-" + suffix, "Té Ceylan 20u " + suffix, 2500);
        addInitialStock(productId2, new BigDecimal("10.000"), new BigDecimal("1500.0000"));
        mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CUSTOMER_CREDIT", "amount": 2500 }]
                                }
                                """.formatted(productId2))
                        .with(user(String.valueOf(cashierId)).authorities(
                                new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE),
                                new SimpleGrantedAuthority(Permissions.SALES_SALE_READ)
                        )))
                .andExpect(status().isForbidden());
    }

    @Test
    void buyXPayYPromotionAppliesOnSale() throws Exception {
        long productId = createProduct("CRD6-" + suffix, "Cerveza Lata " + suffix, 1000);
        addInitialStock(productId, new BigDecimal("20.000"), new BigDecimal("500.0000"));

        long promoId = createBuyXPayYPromotion(productId);

        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 3 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 2000 }]
                                }
                                """.formatted(productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotalGross").value(3000))
                .andExpect(jsonPath("$.discountTotal").value(1000))
                .andExpect(jsonPath("$.totalGross").value(2000))
                .andExpect(jsonPath("$.totalPayable").value(2000))
                .andReturn();

        long saleId = jsonMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asLong();

        // El descuento de la promoción quedó persistido con su referencia (B.9 sale_discounts)
        var discountRow = jdbc.sql("""
                        SELECT promotion_id, source, amount, reason
                        FROM sale_discounts WHERE sale_id = :saleId AND source = 'PROMOTION'
                        """)
                .param("saleId", saleId)
                .query((rs, n) -> new Object[]{
                        rs.getLong("promotion_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason")
                })
                .single();
        assertThat(discountRow[0]).isEqualTo(promoId);
        assertThat((BigDecimal) discountRow[1]).isEqualByComparingTo("1000");
        assertThat((String) discountRow[2]).contains("3x2");

        BigDecimal lineDiscount = jdbc.sql("""
                        SELECT discount_amount FROM sale_lines WHERE sale_id = :saleId
                        """)
                .param("saleId", saleId).query(BigDecimal.class).single();
        assertThat(lineDiscount).isEqualByComparingTo("1000");

        BigDecimal stockAfter = jdbc.sql("SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p")
                .param("s", storeId).param("p", productId).query(BigDecimal.class).single();
        assertThat(stockAfter).isEqualByComparingTo("17.000");
    }

    private long createBuyXPayYPromotion(long productId) throws Exception {
        java.time.Instant now = businessClock.now();
        MvcResult res = mvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "3x2 Cerveza Lata %s",
                                  "promotionType": "BUY_X_PAY_Y",
                                  "priority": 10,
                                  "validFrom": "%s",
                                  "validTo": "%s",
                                  "allStores": true,
                                  "buyQty": 3,
                                  "payQty": 2,
                                  "items": [{ "targetType": "PRODUCT", "targetId": %d }]
                                }
                                """.formatted(suffix,
                                now.minusSeconds(3600),
                                now.plusSeconds(3600),
                                productId))
                        .with(user(String.valueOf(cashierId)).authorities(
                                new SimpleGrantedAuthority(Permissions.PROMOTIONS_PROMOTION_MANAGE)
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private String requestCreditOverride(JsonNode context) throws Exception {
        String body = jsonMapper.writeValueAsString(Map.of(
                "action", "CREDIT_OVER_LIMIT",
                "context", context,
                "supervisorUsername", supervisorUsername,
                "method", "PIN",
                "secret", "1234",
                "reason", "Cliente frecuente autorizado por gerencia"
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
                new SimpleGrantedAuthority(Permissions.SALES_DISCOUNT_APPLY),
                new SimpleGrantedAuthority(Permissions.CUSTOMERS_CREDIT_SELL)
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

    /**
     * Crea un supervisor de prueba con un rol propio que solo contiene
     * {@code customers:credit:override}, para que {@code POST /auth/overrides} lo autorice de forma
     * determinista sin depender de la matriz de permisos del rol semilla SUPERVISOR.
     */
    private long insertSupervisorWithCreditOverride(String username) {
        String pinHash = passwordEncoder.encode("1234");
        long uid = jdbc.sql("""
                        INSERT INTO users (username, full_name, password_hash, pin_hash)
                        VALUES (:u, 'Supervisor Crédito', 'x', :pinHash)
                        RETURNING id
                        """)
                .param("u", username)
                .param("pinHash", pinHash)
                .query(Long.class)
                .single();

        jdbc.sql("INSERT INTO user_stores (user_id, store_id) VALUES (:u, :s) ON CONFLICT DO NOTHING")
                .param("u", uid)
                .param("s", storeId)
                .update();

        long roleId = jdbc.sql("""
                        INSERT INTO roles (code, name, description, system)
                        VALUES (:code, 'Supervisor Crédito ' || :tag, 'Rol de prueba T72', false)
                        RETURNING id
                        """)
                .param("code", "SUPCR-" + suffix)
                .param("tag", suffix)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        INSERT INTO role_permissions (role_id, permission_id)
                        SELECT :r, p.id FROM permissions p WHERE p.code = :code
                        """)
                .param("r", roleId)
                .param("code", Permissions.CUSTOMERS_CREDIT_OVERRIDE)
                .update();

        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r) ON CONFLICT DO NOTHING")
                .param("u", uid)
                .param("r", roleId)
                .update();

        return uid;
    }

    private long insertCustomer(String name, boolean creditEnabled, long creditLimit, long creditBalance) {
        return jdbc.sql("""
                        INSERT INTO customers (name, credit_enabled, credit_limit, credit_balance, active)
                        VALUES (:name, :enabled, :limit, :balance, true)
                        RETURNING id
                        """)
                .param("name", name)
                .param("enabled", creditEnabled)
                .param("limit", BigDecimal.valueOf(creditLimit))
                .param("balance", BigDecimal.valueOf(creditBalance))
                .query(Long.class)
                .single();
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

    private long createProduct(String sku, String name, long priceGross) {
        long productId = jdbc.sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, restricted_type,
                            lot_controlled, active, allow_manual_discount
                        )
                        VALUES (:sku, :name, :cat, 'UNIT', 'UNIT', :price, :cost, false, null, false, true, true)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("cat", categoryId)
                .param("price", priceGross)
                .param("cost", BigDecimal.ZERO)
                .query(Long.class)
                .single();

        long ivaId = jdbc.sql("SELECT id FROM taxes WHERE code = 'IVA'").query(Long.class).single();
        jdbc.sql("INSERT INTO product_taxes (product_id, tax_id) VALUES (:p, :t)")
                .param("p", productId)
                .param("t", ivaId)
                .update();
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
                "Carga inicial test T72",
                List.of(StockLine.of(productId, qty, unitCost, null))
        )));
    }
}
