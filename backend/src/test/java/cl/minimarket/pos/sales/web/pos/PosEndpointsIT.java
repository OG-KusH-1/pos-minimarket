package cl.minimarket.pos.sales.web.pos;

import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.shared.rut.Ean;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de los endpoints POS de búsqueda, resolución de códigos y cotización de
 * carrito ({@code PLAN.md} H.3 T32).
 */
class PosEndpointsIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StockApi stockApi;

    @Autowired
    TransactionTemplate tx;

    private String suffix;
    private long storeId;
    private long terminalId;
    private long userId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        terminalId = jdbc.sql("""
                INSERT INTO terminals (store_id, code, name, active)
                VALUES (:storeId, :code, 'Caja POS Test', true)
                RETURNING id
                """)
                .param("storeId", storeId)
                .param("code", "POS-" + suffix)
                .query(Long.class)
                .single();
        userId = TestData.insertUser(jdbc);
        jdbc.sql("INSERT INTO user_stores (user_id, store_id) VALUES (:u, :s) ON CONFLICT DO NOTHING")
                .param("u", userId)
                .param("s", storeId)
                .update();
        categoryId = createCategory("POS Cat " + suffix);
    }

    @Test
    void searchMatchesWithAndWithoutAccentsCapsAt20AndIncludesStockOnHand() throws Exception {
        String term = "téverde" + suffix;
        long prod1 = createProduct("POS1-" + suffix, "Té Verde Limón " + term, "UNIT", "UNIT", 1490, null, null, null);
        long prod2 = createProduct("POS2-" + suffix, "Te Verde Menta " + term, "UNIT", "UNIT", 1590, null, null, null);
        addInitialStock(prod1, new BigDecimal("12.000"));
        addInitialStock(prod2, new BigDecimal("5.000"));

        // Crear > 20 productos con el mismo prefijo para comprobar el tope máximo de 20
        String batchPrefix = "lim" + suffix;
        for (int i = 0; i < 23; i++) {
            createProduct("LIM" + i + "-" + suffix, "Limonada " + batchPrefix + " " + i, "UNIT", "UNIT", 1000, null, null, null);
        }

        // Búsqueda sin tildes
        MvcResult noAccent = mvc.perform(get("/api/v1/pos/products/search")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .param("q", "teverde" + suffix)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listNoAccent = jsonMapper.readTree(noAccent.getResponse().getContentAsString());
        assertThat(listNoAccent).anyMatch(n -> n.get("productId").asLong() == prod1
                && new BigDecimal("12.000").compareTo(n.get("stockOnHand").decimalValue()) == 0);
        assertThat(listNoAccent).anyMatch(n -> n.get("productId").asLong() == prod2
                && new BigDecimal("5.000").compareTo(n.get("stockOnHand").decimalValue()) == 0);

        // Búsqueda con tildes
        MvcResult withAccent = mvc.perform(get("/api/v1/pos/products/search")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .param("q", "téverde" + suffix)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listWithAccent = jsonMapper.readTree(withAccent.getResponse().getContentAsString());
        assertThat(listWithAccent).anyMatch(n -> n.get("productId").asLong() == prod1);

        // Máximo 20 aunque se pida limit=50
        MvcResult capped = mvc.perform(get("/api/v1/pos/products/search")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .param("q", batchPrefix)
                        .param("limit", "50")
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(jsonMapper.readTree(capped.getResponse().getContentAsString())).hasSize(20);
    }

    @Test
    void resolveSupportsBarcodeScaleShortcutAndHandlesNotFoundOrInactive() throws Exception {
        String barcodeBody = "780" + uniqueDigits(9);
        String regularBarcode = barcodeBody + Ean.checkDigit(barcodeBody);
        String shortcut = "P" + uniqueDigits(5);
        long regularId = createProduct("REG-" + suffix, "Producto Regular " + suffix, "UNIT", "UNIT", 1990, regularBarcode, null, shortcut);
        addInitialStock(regularId, new BigDecimal("8.000"));

        // 1. Código de barras regular
        mvc.perform(get("/api/v1/pos/products/resolve")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .param("code", regularBarcode)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("BARCODE"))
                .andExpect(jsonPath("$.product.productId").value(regularId))
                .andExpect(jsonPath("$.product.stockOnHand").value(8.0));

        // 2. Atajo
        mvc.perform(get("/api/v1/pos/products/resolve")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .param("code", shortcut)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SHORTCUT"))
                .andExpect(jsonPath("$.product.productId").value(regularId));

        // 3. Balanza (prefijo semilla "20", PLU 5 dígitos, peso 0.750 kg)
        String plu = uniqueDigits(5);
        long weightId = createProduct("PES-" + suffix, "Queso Pesable " + suffix, "WEIGHT", "KG", 8990, null, plu, null);
        addInitialStock(weightId, new BigDecimal("3.500"));
        String scaleBody = "20" + plu + "00750";
        String scaleCode = scaleBody + Ean.checkDigit(scaleBody);

        mvc.perform(get("/api/v1/pos/products/resolve")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .param("code", scaleCode)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SCALE"))
                .andExpect(jsonPath("$.quantity").value(0.75))
                .andExpect(jsonPath("$.product.productId").value(weightId))
                .andExpect(jsonPath("$.product.stockOnHand").value(3.5));

        // 4. Código desconocido -> 404 CODE_NOT_FOUND
        mvc.perform(get("/api/v1/pos/products/resolve")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .param("code", "NOEXISTE-" + suffix)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CODE_NOT_FOUND"));

        // 5. Producto inactivo -> 422 PRODUCT_INACTIVE
        mvc.perform(post("/api/v1/products/" + regularId + "/deactivate")
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_DEACTIVATE))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/pos/products/resolve")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .param("code", regularBarcode)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));
    }

    @Test
    void quoteEndpointComputesTotalsRoundingAndRequiredOverrides() throws Exception {
        long productId = createProduct("QT-" + suffix, "Producto Quote " + suffix, "UNIT", "UNIT", 12347, null, null, null);

        mvc.perform(post("/api/v1/pos/cart/quote")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "productId": %d,
                                      "quantity": 1,
                                      "manualDiscount": { "kind": "PERCENT", "value": 10 }
                                    }
                                  ],
                                  "payments": [
                                    { "method": "CASH", "tenderedAmount": 12000 }
                                  ]
                                }
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalGross").value(12347))
                .andExpect(jsonPath("$.discountTotal").value(1235))
                .andExpect(jsonPath("$.totalGross").value(11112))
                .andExpect(jsonPath("$.roundingAdjustment").value(-2))
                .andExpect(jsonPath("$.totalPayable").value(11110))
                .andExpect(jsonPath("$.changeTotal").value(890))
                .andExpect(jsonPath("$.requiredOverrides[0].action").value("DISCOUNT_ITEM_OVER_LIMIT"));
    }

    @Test
    void posEndpointsRequireTerminalHeader() throws Exception {
        mvc.perform(get("/api/v1/pos/products/search")
                        .header("X-Store-Id", storeId)
                        .param("q", "algo")
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/pos/products/resolve")
                        .header("X-Store-Id", storeId)
                        .param("code", "123456")
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/pos/cart/quote")
                        .header("X-Store-Id", storeId)
                        .with(user(String.valueOf(userId)).authorities(new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    { "productId": 1, "quantity": 1 }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private long createCategory(String name) {
        return jdbc.sql("INSERT INTO categories (name, active) VALUES (:name, true) RETURNING id")
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
            String regularBarcode,
            String scalePlu,
            String shortcutCode
    ) throws Exception {
        String barcodesJson = "[]";
        if (regularBarcode != null) {
            barcodesJson = """
                    [{"code": "%s", "barcodeType": "EAN13", "isPrimary": true}]
                    """.formatted(regularBarcode);
        } else if (scalePlu != null) {
            barcodesJson = """
                    [{"code": "%s", "barcodeType": "SCALE_PLU", "isPrimary": true}]
                    """.formatted(scalePlu);
        }

        String shortcutJson = shortcutCode == null ? "null" : "\"" + shortcutCode + "\"";
        MvcResult res = mvc.perform(post("/api/v1/products")
                        .with(user(String.valueOf(userId)).authorities(
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_CREATE),
                                new SimpleGrantedAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s",
                                  "name": "%s",
                                  "categoryId": %d,
                                  "saleType": "%s",
                                  "unitOfMeasure": "%s",
                                  "vatExempt": false,
                                  "initialPriceGross": %d,
                                  "shortcutCode": %s,
                                  "barcodes": %s
                                }
                                """.formatted(sku, name, categoryId, saleType, unitOfMeasure, priceGross, shortcutJson, barcodesJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void addInitialStock(long productId, BigDecimal qty) {
        tx.executeWithoutResult(status -> stockApi.apply(new StockCommand(
                storeId,
                StockMovementType.INITIAL,
                StockSourceType.INITIAL_LOAD,
                productId,
                userId,
                "T32-INIT",
                List.of(StockLine.of(productId, qty))
        )));
    }

    private static String uniqueDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        return sb.toString();
    }
}
