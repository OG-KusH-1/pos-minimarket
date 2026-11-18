package cl.minimarket.pos.flow;

import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de punta a punta del flujo completo de venta POS (T45, {@code PLAN.md} H.3/H.5):
 * <ul>
 *   <li>Flujo HTTP completo con usuario autenticado por {@code /auth/login}: selección de local y
 *       terminal, apertura de turno, resolución de producto, cotización, autorización de tarjeta,
 *       venta con redondeo de efectivo e idempotencia, ticket, descuento en stock/kardex y cierre
 *       de caja con arqueo y comprobante JSON/PDF.</li>
 *   <li>Concurrencia de 20 hilos simultáneos vendiendo un mismo producto con stock limitado: verifica
 *       que el stock nunca sea negativo, que exactamente {@code stockInicial / cantidadPorVenta}
 *       ventas sean aceptadas, que las restantes fallen limpiamente con {@code STOCK_INSUFFICIENT}
 *       y que el kardex {@code SALE} y el resumen de caja permanezcan consistentes.</li>
 * </ul>
 */
class SaleFlowIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StockApi stockApi;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    PasswordEncoder passwordEncoder;

    /**
     * Ejecuta el flujo HTTP completo de punta a punta:
     * {@code login -> local/terminal -> abrir caja -> cotizar/vender (con idempotencia) -> cerrar caja y comprobante}.
     */
    @Test
    void fullSaleFlowFromLoginToCashCloseAndReceiptIsCoherent() throws Exception {
        String sfx = uniqueSuffix();
        String username = "flcaj_" + sfx;
        String rawPassword = "CajeroFlow#2026";
        long storeId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        long terminalId = insertTerminal(storeId, "FL-" + sfx.substring(0, 5).toUpperCase());
        long cashierId = insertCashierUser(username, rawPassword, storeId);

        long categoryId = insertCategory("Cat Flujo " + sfx);
        String sku = "FLSKU-" + sfx;
        long productId = insertUnitProduct(categoryId, sku, "Café Flujo 250g " + sfx,
                2997L, new BigDecimal("1500.0000"));
        addInitialStock(storeId, cashierId, productId, new BigDecimal("10.000"), new BigDecimal("1500.0000"));

        // 1. Login HTTP real para obtener JWT y cookie pos_refresh
        LoginSession session = login(username, rawPassword, "198.51.100.61");

        // 2. Selección de local y terminal vía endpoints de seguridad y organización
        MvcResult storesRes = mvc.perform(get("/api/v1/auth/me/stores")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode storesJson = JSON.readTree(storesRes.getResponse().getContentAsString());
        assertThat(storesJson).anyMatch(s -> s.get("id").asLong() == storeId);

        MvcResult terminalsRes = mvc.perform(get("/api/v1/stores/{storeId}/terminals", storeId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode terminalsJson = JSON.readTree(terminalsRes.getResponse().getContentAsString());
        assertThat(terminalsJson).anyMatch(t -> t.get("id").asLong() == terminalId);

        mvc.perform(post("/api/v1/auth/session/terminal")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .cookie(session.refreshCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalId\": " + terminalId + "}"))
                .andExpect(status().isNoContent());

        // 3. Verificar que no existe turno abierto y abrir caja con idempotencia
        mvc.perform(get("/api/v1/cash-sessions/current")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId))
                .andExpect(status().isNoContent());

        MvcResult openCashRes = mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 50000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openingAmount").value(50000))
                .andExpect(jsonPath("$.store.id").value(storeId))
                .andExpect(jsonPath("$.terminal.id").value(terminalId))
                .andExpect(jsonPath("$.cashier.id").value(cashierId))
                .andReturn();

        long cashSessionId = JSON.readTree(openCashRes.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(get("/api/v1/cash-sessions/current")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cashSessionId));

        // 4. Resolver producto, autorizar pago tarjeta, cotizar carrito y crear venta (con replay idempotente)
        MvcResult resolveRes = mvc.perform(get("/api/v1/pos/products/resolve")
                        .param("code", sku)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SKU"))
                .andExpect(jsonPath("$.product.productId").value(productId))
                .andExpect(jsonPath("$.product.priceGross").value(2997))
                .andReturn();
        assertThat(JSON.readTree(resolveRes.getResponse().getContentAsString())
                .path("product").path("stockOnHand").decimalValue())
                .isEqualByComparingTo("10.000");

        MvcResult cardRes = mvc.perform(post("/api/v1/pos/card-payments")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\": \"DEBIT\", \"amount\": 1000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn();
        long cardAuthId = JSON.readTree(cardRes.getResponse().getContentAsString()).get("id").asLong();

        // Total bruto = 2997. Con DEBIT 1000, efectivo adeudado = 1997 -> redondeo legal +3 -> 2000.
        // Entregado efectivo = 5000 -> vuelto = 3000, totalPayable = 3000, paidTotal = 6000.
        String quoteBody = """
                {
                  "lines": [{ "productId": %d, "quantity": 1 }],
                  "payments": [
                    { "method": "DEBIT", "amount": 1000 },
                    { "method": "CASH", "tenderedAmount": 5000 }
                  ]
                }
                """.formatted(productId);

        mvc.perform(post("/api/v1/pos/cart/quote")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quoteBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalGross").value(2997))
                .andExpect(jsonPath("$.roundingAdjustment").value(3))
                .andExpect(jsonPath("$.totalPayable").value(3000))
                .andExpect(jsonPath("$.changeTotal").value(3000));

        UUID saleIdempotencyKey = UUID.randomUUID();
        String salePayload = """
                {
                  "lines": [{ "productId": %d, "quantity": 1 }],
                  "payments": [
                    { "method": "DEBIT", "amount": 1000, "cardAuthorizationId": %d },
                    { "method": "CASH", "tenderedAmount": 5000 }
                  ]
                }
                """.formatted(productId, cardAuthId);

        MvcResult saleRes = mvc.perform(post("/api/v1/sales")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", saleIdempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(salePayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.subtotalGross").value(2997))
                .andExpect(jsonPath("$.roundingAdjustment").value(3))
                .andExpect(jsonPath("$.totalPayable").value(3000))
                .andExpect(jsonPath("$.paidTotal").value(6000))
                .andExpect(jsonPath("$.changeTotal").value(3000))
                .andReturn();

        JsonNode saleJson = JSON.readTree(saleRes.getResponse().getContentAsString());
        long saleId = saleJson.get("id").asLong();
        String saleNumber = saleJson.get("number").asText();

        // Reintento idempotente con la misma Idempotency-Key -> misma venta, sin doble cobro ni doble descuento
        MvcResult replayRes = mvc.perform(post("/api/v1/sales")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", saleIdempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(salePayload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();
        assertThat(JSON.readTree(replayRes.getResponse().getContentAsString()).get("id").asLong())
                .isEqualTo(saleId);

        // Ticket de venta disponible
        mvc.perform(get("/api/v1/sales/{id}/ticket", saleId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(saleNumber))
                .andExpect(jsonPath("$.reprintCount").value(0));

        // Verificar descuento único en stock y kardex SALE
        BigDecimal stockAfter = appJdbc().sql("""
                        SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p
                        """)
                .param("s", storeId)
                .param("p", productId)
                .query(BigDecimal.class)
                .single();
        assertThat(stockAfter).isEqualByComparingTo("9.000");

        List<BigDecimal> saleDeltas = appJdbc().sql("""
                        SELECT quantity_delta FROM stock_movements
                        WHERE store_id = :s AND product_id = :p AND source_type = 'SALE' AND source_id = :saleId
                        """)
                .param("s", storeId)
                .param("p", productId)
                .param("saleId", saleId)
                .query(BigDecimal.class)
                .list();
        assertThat(saleDeltas).hasSize(1);
        assertThat(saleDeltas.getFirst()).isEqualByComparingTo("-1.000");

        // 5. Resumen previo al cierre, cierre de caja con arqueo exacto y comprobante JSON/PDF
        mvc.perform(get("/api/v1/cash-sessions/{id}/summary", cashSessionId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingAmount").value(50000))
                .andExpect(jsonPath("$.expectedCash").value(52000))
                .andExpect(jsonPath("$.salesCount").value(1));

        mvc.perform(post("/api/v1/cash-sessions/{id}/close", cashSessionId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "counts": [
                                    {
                                      "paymentMethod": "CASH",
                                      "countedAmount": 52000,
                                      "denominationDetail": {"10000": 5, "2000": 1}
                                    },
                                    {
                                      "paymentMethod": "DEBIT",
                                      "countedAmount": 1000
                                    }
                                  ],
                                  "notes": "Cierre flujo completo T45"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.expectedTotal").value(53000))
                .andExpect(jsonPath("$.countedTotal").value(53000))
                .andExpect(jsonPath("$.differenceTotal").value(0))
                .andExpect(jsonPath("$.requiresDifferenceApproval").value(false));

        // Ya no debe existir turno abierto en la terminal
        mvc.perform(get("/api/v1/cash-sessions/current")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId))
                .andExpect(status().isNoContent());

        // Comprobante de cierre JSON y PDF coherentes
        mvc.perform(get("/api/v1/cash-sessions/{id}/closing-receipt", cashSessionId)
                        .param("format", "json")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.id").value(cashSessionId))
                .andExpect(jsonPath("$.session.status").value("CLOSED"))
                .andExpect(jsonPath("$.session.differenceTotal").value(0))
                .andExpect(jsonPath("$.summary.expectedCash").value(52000))
                .andExpect(jsonPath("$.summary.salesCount").value(1))
                .andExpect(jsonPath("$.store.id").value(storeId));

        MvcResult pdfRes = mvc.perform(get("/api/v1/cash-sessions/{id}/closing-receipt", cashSessionId)
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        byte[] pdfBytes = pdfRes.getResponse().getContentAsByteArray();
        assertThat(pdfBytes.length).isGreaterThan(4);
        assertThat(new String(pdfBytes, 0, 4)).isEqualTo("%PDF");
    }

    /**
     * Ejecuta 20 hilos simultáneos vendiendo el mismo producto con stock limitado (18 unidades,
     * 3 unidades por venta): verifica que {@code stock final >= 0}, que
     * {@code ventas aceptadas == unidades iniciales / cantidad por venta} (6 aceptadas, 14
     * rechazadas limpiamente con {@code STOCK_INSUFFICIENT}) y que BD + kardex {@code SALE} quedan
     * completamente consistentes.
     */
    @Test
    void twentyConcurrentSalesOnLimitedStock_neverNegativeAndKardexConsistent() throws Exception {
        String sfx = uniqueSuffix();
        String username = "flconc_" + sfx;
        String rawPassword = "CajeroConc#2026";
        long storeId = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        long terminalId = insertTerminal(storeId, "CC-" + sfx.substring(0, 5).toUpperCase());
        long cashierId = insertCashierUser(username, rawPassword, storeId);

        long categoryId = insertCategory("Cat Concurrencia " + sfx);
        long productId = insertUnitProduct(categoryId, "CONC-" + sfx, "Azúcar 1kg " + sfx,
                1000L, new BigDecimal("600.0000"));

        final int initialUnits = 18;
        final int qtyPerSale = 3;
        final int threads = 20;
        final int expectedAccepted = initialUnits / qtyPerSale; // 6
        final int expectedRejected = threads - expectedAccepted; // 14

        addInitialStock(storeId, cashierId, productId,
                BigDecimal.valueOf(initialUnits), new BigDecimal("600.0000"));

        LoginSession session = login(username, rawPassword, "198.51.100.62");

        mvc.perform(post("/api/v1/auth/session/terminal")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .cookie(session.refreshCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalId\": " + terminalId + "}"))
                .andExpect(status().isNoContent());

        MvcResult openCashRes = mvc.perform(post("/api/v1/cash-sessions")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingAmount\": 50000}"))
                .andExpect(status().isCreated())
                .andReturn();
        long cashSessionId = JSON.readTree(openCashRes.getResponse().getContentAsString()).get("id").asLong();

        String saleBody = """
                {
                  "lines": [{ "productId": %d, "quantity": %d }],
                  "payments": [{ "method": "CASH", "tenderedAmount": 3000 }]
                }
                """.formatted(productId, qtyPerSale);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acceptedCount = new AtomicInteger();
        AtomicInteger stockInsufficientCount = new AtomicInteger();
        Set<Long> createdSaleIds = java.util.Collections.synchronizedSet(new HashSet<>());

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();

                    MvcResult res = mvc.perform(post("/api/v1/sales")
                                    .header("Authorization", "Bearer " + session.accessToken())
                                    .header("X-Store-Id", storeId)
                                    .header("X-Terminal-Id", terminalId)
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(saleBody))
                            .andReturn();

                    int httpStatus = res.getResponse().getStatus();
                    String content = res.getResponse().getContentAsString();
                    if (httpStatus == HttpStatus.CREATED.value()) {
                        JsonNode body = JSON.readTree(content);
                        assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
                        createdSaleIds.add(body.get("id").asLong());
                        acceptedCount.incrementAndGet();
                    } else if (httpStatus == HttpStatus.UNPROCESSABLE_CONTENT.value()) {
                        JsonNode body = JSON.readTree(content);
                        assertThat(body.get("code").asText()).isEqualTo("STOCK_INSUFFICIENT");
                        stockInsufficientCount.incrementAndGet();
                    } else {
                        throw new AssertionError("Respuesta HTTP inesperada en venta concurrente: "
                                + httpStatus + " cuerpo=" + content);
                    }
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        // Verificar conteos de aceptación y rechazo limpio
        assertThat(acceptedCount.get()).isEqualTo(expectedAccepted);
        assertThat(stockInsufficientCount.get()).isEqualTo(expectedRejected);
        assertThat(acceptedCount.get() + stockInsufficientCount.get()).isEqualTo(threads);
        assertThat(createdSaleIds).hasSize(expectedAccepted);

        // Verificar stock final >= 0 y exactamente consistente con ventas aceptadas
        BigDecimal finalStock = appJdbc().sql("""
                        SELECT quantity FROM stock WHERE store_id = :s AND product_id = :p
                        """)
                .param("s", storeId)
                .param("p", productId)
                .query(BigDecimal.class)
                .single();
        assertThat(finalStock).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        BigDecimal expectedFinalStock = BigDecimal.valueOf(initialUnits - (long) expectedAccepted * qtyPerSale);
        assertThat(finalStock).isEqualByComparingTo(expectedFinalStock);

        // Verificar ventas persistidas en BD para el turno
        Long persistedSalesCount = appJdbc().sql("""
                        SELECT COUNT(*) FROM sales
                        WHERE cash_session_id = :csId AND status = 'COMPLETED'
                        """)
                .param("csId", cashSessionId)
                .query(Long.class)
                .single();
        assertThat(persistedSalesCount).isEqualTo(expectedAccepted);

        // Verificar consistencia completa en kardex SALE
        List<BigDecimal> deltas = appJdbc().sql("""
                        SELECT quantity_delta FROM stock_movements
                        WHERE store_id = :s AND product_id = :p AND source_type = 'SALE'
                        """)
                .param("s", storeId)
                .param("p", productId)
                .query(BigDecimal.class)
                .list();
        assertThat(deltas).hasSize(expectedAccepted);
        assertThat(deltas).allSatisfy(d -> assertThat(d).isEqualByComparingTo(BigDecimal.valueOf(-qtyPerSale)));

        List<BigDecimal> balancesAfter = appJdbc().sql("""
                        SELECT balance_after FROM stock_movements
                        WHERE store_id = :s AND product_id = :p AND source_type = 'SALE'
                        """)
                .param("s", storeId)
                .param("p", productId)
                .query(BigDecimal.class)
                .list();
        assertThat(balancesAfter).allSatisfy(b -> assertThat(b).isGreaterThanOrEqualTo(BigDecimal.ZERO));

        BigDecimal sumSaleDeltas = appJdbc().sql("""
                        SELECT COALESCE(SUM(quantity_delta), 0) FROM stock_movements
                        WHERE store_id = :s AND product_id = :p AND source_type = 'SALE'
                        """)
                .param("s", storeId)
                .param("p", productId)
                .query(BigDecimal.class)
                .single();
        assertThat(BigDecimal.valueOf(initialUnits).add(sumSaleDeltas)).isEqualByComparingTo(finalStock);

        // Verificar coherencia del resumen de caja con las ventas aceptadas (50000 + 6 * 3000 = 68000)
        mvc.perform(get("/api/v1/cash-sessions/{id}/summary", cashSessionId)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesCount").value(expectedAccepted))
                .andExpect(jsonPath("$.expectedCash").value(50000 + expectedAccepted * 3000));
    }

    private LoginSession login(String username, String password, String remoteIp) throws Exception {
        MvcResult loginResult = mvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(remoteIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("pos_refresh");
        assertThat(refreshCookie).isNotNull();
        String accessToken = JSON.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();
        return new LoginSession(accessToken, refreshCookie);
    }

    private long insertTerminal(long storeId, String code) {
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

    private long insertCashierUser(String username, String rawPassword, long storeId) {
        long userId = ownerJdbc().sql("""
                        INSERT INTO users (username, full_name, password_hash, must_change_password, active)
                        VALUES (:username, :fullName, :pwHash, false, true)
                        RETURNING id
                        """)
                .param("username", username)
                .param("fullName", "Cajero " + username)
                .param("pwHash", passwordEncoder.encode(rawPassword))
                .query(Long.class)
                .single();

        ownerJdbc().sql("""
                        INSERT INTO user_stores (user_id, store_id, is_default)
                        VALUES (:userId, :storeId, true)
                        """)
                .param("userId", userId)
                .param("storeId", storeId)
                .update();

        long cashierRoleId = ownerJdbc().sql("SELECT id FROM roles WHERE code = 'CASHIER'")
                .query(Long.class)
                .single();

        ownerJdbc().sql("INSERT INTO user_roles (user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", userId)
                .param("roleId", cashierRoleId)
                .update();

        return userId;
    }

    private long insertCategory(String name) {
        return ownerJdbc().sql("""
                        INSERT INTO categories (name, active) VALUES (:name, true) RETURNING id
                        """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertUnitProduct(
            long categoryId,
            String sku,
            String name,
            long priceGross,
            BigDecimal avgCost
    ) {
        long productId = ownerJdbc().sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, lot_controlled, active, allow_manual_discount
                        )
                        VALUES (:sku, :name, :catId, 'UNIT', 'UNIT', :price, :avgCost, false, false, true, true)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("catId", categoryId)
                .param("price", priceGross)
                .param("avgCost", avgCost)
                .query(Long.class)
                .single();

        long ivaId = ownerJdbc().sql("SELECT id FROM taxes WHERE code = 'IVA'")
                .query(Long.class)
                .single();
        ownerJdbc().sql("INSERT INTO product_taxes (product_id, tax_id) VALUES (:p, :t)")
                .param("p", productId)
                .param("t", ivaId)
                .update();

        return productId;
    }

    private void addInitialStock(
            long storeId,
            long actorUserId,
            long productId,
            BigDecimal quantity,
            BigDecimal unitCost
    ) {
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
                actorUserId,
                "Carga inicial test T45",
                List.of(StockLine.of(productId, quantity, unitCost, null))
        )));
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private record LoginSession(String accessToken, Cookie refreshCookie) {
    }
}
