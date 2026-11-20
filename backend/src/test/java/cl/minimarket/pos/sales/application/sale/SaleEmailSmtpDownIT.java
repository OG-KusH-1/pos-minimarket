package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.notification.application.EmailOutboxService;
import cl.minimarket.pos.notification.domain.OutboundEmail;
import cl.minimarket.pos.notification.domain.OutboundEmailRepository;
import cl.minimarket.pos.notification.domain.OutboundEmailStatus;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba de integración del envío del comprobante con SMTP caído (HU-POS-11 escenario 2):
 * {@code POST /sales/{id}/email} responde 202 y la operación de negocio no falla; el correo queda
 * en la outbox y el reintento lo marca con el error, dejándolo pendiente para el siguiente ciclo
 * del job de {@code notification}.
 */
class SaleEmailSmtpDownIT extends AbstractIntegrationIT {

    /** Puerto local sin escucha: la conexión SMTP se rechaza de inmediato. */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo reservar un puerto cerrado de prueba", ex);
        }
    }

    private static final int SMTP_CLOSED_PORT = closedPort();

    @DynamicPropertySource
    static void deadSmtpProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> SMTP_CLOSED_PORT);
        registry.add("spring.mail.properties.mail.smtp.connectiontimeout", () -> "1500");
        registry.add("spring.mail.properties.mail.smtp.timeout", () -> "1500");
        registry.add("pos.notification.scheduler-enabled", () -> "false");
    }

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
    EmailOutboxService emailOutboxService;

    @Autowired
    OutboundEmailRepository outboundEmailRepository;

    private String suffix;
    private long storeId;
    private long cashierId;
    private long terminalId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        cashierId = insertUserWithRole("cajs_" + suffix, "Cajero SMTP " + suffix, null, "CASHIER");
        terminalId = insertTerminal(storeId, "SD-" + suffix);
        openCashSession(storeId, terminalId, cashierId);
        categoryId = createCategory("Cat SMTP " + suffix);
    }

    @Test
    void emailEnqueueSucceedsWithSmtpDownAndSaleIsNotAffected() throws Exception {
        long productId = createProduct("SMTP1-" + suffix, "Mantequilla 500g " + suffix, 4200);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("2600.0000"));

        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 4200 }]
                                }
                                """.formatted(productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();
        long saleId = jsonMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asLong();

        // Con SMTP caído el endpoint igual responde 202: solo se encola (ADR-10, patrón outbox)
        String recipient = "smtp-down-" + suffix + "@example.cl";
        mvc.perform(post("/api/v1/sales/{id}/email", saleId)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s" }
                                """.formatted(recipient))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isAccepted());

        // El despacho falla sin derribar nada: intento registrado y correo queda pendiente
        int sent = emailOutboxService.processPending();
        assertThat(sent).isEqualTo(0);

        OutboundEmail failed = outboundEmailRepository.findAll().stream()
                .filter(e -> recipient.equals(e.getToAddress()))
                .findFirst()
                .orElseThrow();
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).isNotBlank();
        // Sin alcanzar maxAttempts sigue PENDING para que el job reintente en el próximo ciclo
        assertThat(failed.getStatus()).isEqualTo(OutboundEmailStatus.PENDING);

        // La venta sigue intacta y COMPLETED
        String saleStatus = jdbc.sql("SELECT status FROM sales WHERE id = :id")
                .param("id", saleId).query(String.class).single();
        assertThat(saleStatus).isEqualTo("COMPLETED");

        // Y la venta puede seguir consultándose por su ticket sin error
        mvc.perform(post("/api/v1/sales/{id}/email", saleId)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s" }
                                """.formatted(recipient))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isAccepted());
    }

    private RequestPostProcessor cashierAuth(long uid) {
        return user(String.valueOf(uid)).authorities(
                new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE),
                new SimpleGrantedAuthority(Permissions.SALES_SALE_READ)
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
                "Carga inicial test T72 (SMTP caído)",
                List.of(StockLine.of(productId, qty, unitCost, null))
        )));
    }
}
