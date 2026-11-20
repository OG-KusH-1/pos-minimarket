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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del envío del comprobante por correo ({@code POST /sales/{id}/email},
 * HU-POS-11) contra la outbox real ({@code outbound_emails}) y un contenedor Mailpit.
 */
class SaleEmailOutboxIT extends AbstractIntegrationIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.33.1")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
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
    EmailOutboxService emailOutboxService;

    @Autowired
    OutboundEmailRepository outboundEmailRepository;

    @Autowired
    StockApi stockApi;

    private String suffix;
    private long storeId;
    private long cashierId;
    private long terminalId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        cashierId = insertUserWithRole("cajm_" + suffix, "Cajero Mail " + suffix, null, "CASHIER");
        terminalId = insertTerminal(storeId, "ML-" + suffix);
        openCashSession(storeId, terminalId, cashierId);
        categoryId = createCategory("Cat Mail " + suffix);
    }

    @Test
    void emailEndpointReturns202AndOutboxDeliversToMailpit() throws Exception {
        long productId = createProduct("MAIL1-" + suffix, "Café Instantáneo " + suffix, 3500);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("2000.0000"));

        // Venta al contado de referencia
        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 3500 }]
                                }
                                """.formatted(productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sale = jsonMapper.readTree(saleRes.getResponse().getContentAsString());
        long saleId = sale.get("id").asLong();
        String saleNumber = sale.get("number").asText();

        String recipient = "cliente-" + suffix + "@example.cl";
        mvc.perform(post("/api/v1/sales/{id}/email", saleId)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s" }
                                """.formatted(recipient))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isAccepted());

        // El correo quedó encolado en la outbox dentro de la misma operación
        OutboundEmail queued = outboundEmailRepository.findAll().stream()
                .filter(e -> recipient.equals(e.getToAddress()))
                .findFirst()
                .orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(OutboundEmailStatus.PENDING);
        assertThat(queued.getTemplate()).isEqualTo("sale-ticket");
        assertThat(queued.getSubject()).isEqualTo("Comprobante de venta " + saleNumber);
        assertThat(queued.getPayload()).containsEntry("saleNumber", saleNumber);

        // Despachar la outbox y verificar la recepción real en Mailpit
        int sent = emailOutboxService.processPending();
        assertThat(sent).isGreaterThanOrEqualTo(1);

        OutboundEmail dispatched = outboundEmailRepository.findById(queued.getId()).orElseThrow();
        assertThat(dispatched.getStatus()).isEqualTo(OutboundEmailStatus.SENT);
        assertThat(dispatched.getSentAt()).isNotNull();

        String apiUrl = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(apiUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode root = jsonMapper.readTree(response.body());
        boolean found = false;
        for (JsonNode msg : root.path("messages")) {
            if (("Comprobante de venta " + saleNumber).equals(msg.path("Subject").asText())) {
                found = true;
                assertThat(extractToAddresses(msg.path("To"))).contains(recipient);
                break;
            }
        }
        assertThat(found).isTrue();
    }

    /**
     * Extrae las direcciones de correo del campo {@code To} de un resumen de Mailpit.
     *
     * <p>Mailpit devuelve el campo como arreglo de objetos {@code {Name, Address}}, pero en
     * algunas respuestas el primer nivel es un arreglo de grupos; este método maneja ambos
     * casos de forma defensiva.
     */
    private static List<String> extractToAddresses(JsonNode toNode) {
        List<String> addresses = new ArrayList<>();
        if (!toNode.isArray()) {
            return addresses;
        }
        for (JsonNode item : toNode) {
            if (item.isArray()) {
                for (JsonNode inner : item) {
                    addresses.addAll(extractToAddresses(inner));
                }
            } else if (item.isObject()) {
                JsonNode address = item.path("Address");
                if (address.isTextual()) {
                    addresses.add(address.asText());
                }
            } else if (item.isTextual()) {
                addresses.add(item.asText());
            }
        }
        return addresses;
    }

    @Test
    void emailEndpointValidatesRecipientAndUnknownSale() throws Exception {
        long productId = createProduct("MAIL2-" + suffix, "Sal Gruesa " + suffix, 1200);
        addInitialStock(productId, new BigDecimal("10.000"), new BigDecimal("600.0000"));

        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [{ "productId": %d, "quantity": 1 }],
                                  "payments": [{ "method": "CASH", "tenderedAmount": 1200 }]
                                }
                                """.formatted(productId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andReturn();
        long saleId = jsonMapper.readTree(saleRes.getResponse().getContentAsString()).get("id").asLong();

        // Destinatario obligatorio → 400
        mvc.perform(post("/api/v1/sales/{id}/email", saleId)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isBadRequest());

        // Venta inexistente en el local → 404
        mvc.perform(post("/api/v1/sales/{id}/email", 999999999L)
                        .header("X-Store-Id", storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "alguien-%s@example.cl" }
                                """.formatted(suffix))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Venta de otro local: el filtro de contexto responde 403 antes de la búsqueda → 403
        long otherStoreId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        mvc.perform(post("/api/v1/sales/{id}/email", saleId)
                        .header("X-Store-Id", otherStoreId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "otro-%s@example.cl" }
                                """.formatted(suffix))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isForbidden());
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
                "Carga inicial test T72 (correo)",
                List.of(StockLine.of(productId, qty, unitCost, null))
        )));
    }
}
