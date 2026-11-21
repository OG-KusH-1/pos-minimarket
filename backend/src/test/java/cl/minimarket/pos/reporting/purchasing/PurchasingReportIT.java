package cl.minimarket.pos.reporting.purchasing;

import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del reporte de compras por proveedor ({@code T85}, {@code HU-REP-08}).
 */
class PurchasingReportIT extends AbstractIntegrationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate FROM = LocalDate.of(2031, 4, 1);
    private static final LocalDate TO = LocalDate.of(2031, 4, 30);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private String sfx;
    private long storeA;
    private long storeB;
    private long userId;
    private long restrictedUserId;
    private long productId;
    private long supplier1;
    private long supplier2;
    private long supplierDraftOnly;
    private int seq;

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeA = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        storeB = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        userId = TestData.insertUser(jdbc);
        restrictedUserId = TestData.insertUser(jdbc);
        jdbc.sql("INSERT INTO user_stores (user_id, store_id, is_default) VALUES (:u, :s, true)")
                .param("u", restrictedUserId)
                .param("s", storeA)
                .update();

        long catId = jdbc.sql("INSERT INTO categories (name, active) VALUES (:n, true) RETURNING id")
                .param("n", "Compras P85 " + sfx).query(Long.class).single();
        productId = jdbc.sql("""
                        INSERT INTO products (
                            sku, name, category_id, sale_type, unit_of_measure,
                            price_gross, avg_cost, vat_exempt, lot_controlled, active
                        )
                        VALUES (:sku, :name, :cat, 'UNIT', 'UNIT', 1000, 500.0000, false, false, true)
                        RETURNING id
                        """)
                .param("sku", "P85-" + sfx).param("name", "Producto P85 " + sfx).param("cat", catId)
                .query(Long.class).single();

        supplier1 = insertSupplier("Proveedor Uno P85 " + sfx);
        supplier2 = insertSupplier("Proveedor Dos P85 " + sfx);
        supplierDraftOnly = insertSupplier("Proveedor Draft P85 " + sfx);

        // Proveedor 1:
        // OC1 enviada 04-05 14:00Z, 100 pedidas / 80 recibidas (PARTIALLY_RECEIVED), recibida 04-07 14:00Z (2 días), neto 40000
        // OC2 enviada 04-10 14:00Z, 100 pedidas / 100 recibidas (RECEIVED), recibida 04-14 14:00Z (4 días), neto 60000
        // => orders=2, receipts=2, netTotal=100000, avgLeadTimeDays=3.00, fillRatePct=90.00
        OffsetDateTime po1Sent = OffsetDateTime.of(2031, 4, 5, 14, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime po2Sent = OffsetDateTime.of(2031, 4, 10, 14, 0, 0, 0, ZoneOffset.UTC);
        long po1 = insertPo(storeA, supplier1, "PARTIALLY_RECEIVED", po1Sent, 40000);
        insertPoLine(po1, "100.000", "80.000", "500.0000", 40000);
        long po2 = insertPo(storeA, supplier1, "RECEIVED", po2Sent, 60000);
        insertPoLine(po2, "100.000", "100.000", "600.0000", 60000);

        insertReceipt(storeA, supplier1, po1, "CONFIRMED", po1Sent.plusDays(2), 40000);
        insertReceipt(storeA, supplier1, po2, "CONFIRMED", po2Sent.plusDays(4), 60000);

        // Borrador y cancelada para supplier1: no deben contabilizarse
        long poCancelled = insertPo(storeA, supplier1, "CANCELLED", po1Sent, 99999);
        insertPoLine(poCancelled, "50.000", "0.000", "500.0000", 25000);
        insertReceipt(storeA, supplier1, null, "DRAFT", po1Sent.plusDays(1), 88888);

        // Proveedor 2: recepción confirmada sin OC (neto 25000) -> orders=0, receipts=1, leadTime=null, fillRate=null
        insertReceipt(storeA, supplier2, null, "CONFIRMED",
                OffsetDateTime.of(2031, 4, 15, 15, 0, 0, 0, ZoneOffset.UTC), 25000);

        // Proveedor 3: solo OC en DRAFT -> no debe aparecer
        long poDraft = insertPo(storeA, supplierDraftOnly, "DRAFT", null, 15000);
        insertPoLine(poDraft, "30.000", "0.000", "500.0000", 15000);
    }

    @Test
    void computesOrdersReceiptsNetTotalLeadTimeAndFillRateBySupplier() throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/reports/purchases-by-supplier")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_PURCHASING_READ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = JSON.readTree(res.getResponse().getContentAsString());
        Map<Long, JsonNode> bySupplier = new LinkedHashMap<>();
        for (JsonNode row : root.get("rows")) {
            bySupplier.put(row.get("supplierId").asLong(), row);
        }

        assertThat(bySupplier).containsOnlyKeys(supplier1, supplier2);
        assertThat(bySupplier).doesNotContainKey(supplierDraftOnly);

        JsonNode s1 = bySupplier.get(supplier1);
        assertThat(s1.get("orders").asLong()).isEqualTo(2L);
        assertThat(s1.get("receipts").asLong()).isEqualTo(2L);
        assertThat(s1.get("netTotal").decimalValue()).isEqualByComparingTo("100000");
        assertThat(s1.get("avgLeadTimeDays").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(s1.get("fillRatePct").decimalValue()).isEqualByComparingTo("90.00");

        JsonNode s2 = bySupplier.get(supplier2);
        assertThat(s2.get("orders").asLong()).isZero();
        assertThat(s2.get("receipts").asLong()).isEqualTo(1L);
        assertThat(s2.get("netTotal").decimalValue()).isEqualByComparingTo("25000");
        assertThat(s2.get("avgLeadTimeDays").isNull()).isTrue();
        assertThat(s2.get("fillRatePct").isNull()).isTrue();

        JsonNode totals = root.get("totals");
        assertThat(totals.get("supplierId").isNull()).isTrue();
        assertThat(totals.get("orders").asLong()).isEqualTo(2L);
        assertThat(totals.get("receipts").asLong()).isEqualTo(3L);
        assertThat(totals.get("netTotal").decimalValue()).isEqualByComparingTo("125000");
        assertThat(totals.get("avgLeadTimeDays").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(totals.get("fillRatePct").decimalValue()).isEqualByComparingTo("90.00");
    }

    @Test
    void rejectsUnauthorizedAndUnassignedStore() throws Exception {
        mvc.perform(get("/api/v1/reports/purchases-by-supplier")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("X-Store-Id", storeA)
                        .with(user("1").authorities(new SimpleGrantedAuthority(Permissions.REPORT_SALES_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(get("/api/v1/reports/purchases-by-supplier")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("storeIds", storeA + "," + storeB)
                        .header("X-Store-Id", storeA)
                        .with(user(String.valueOf(restrictedUserId))
                                .authorities(new SimpleGrantedAuthority(Permissions.REPORT_PURCHASING_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));
    }

    private long insertSupplier(String legalName) {
        String rut = ThreadLocalRandom.current().nextInt(70_000_000, 99_999_999) + "-" + ((++seq) % 10);
        return jdbc.sql("""
                        INSERT INTO suppliers (rut, legal_name, payment_terms_type, lead_time_days, active)
                        VALUES (:rut, :name, 'CREDIT', 2, true)
                        RETURNING id
                        """)
                .param("rut", rut).param("name", legalName)
                .query(Long.class).single();
    }

    private long insertPo(long storeId, long supplierId, String status, OffsetDateTime sentAt, long netTotal) {
        OffsetDateTime createdAt = sentAt != null
                ? sentAt.minusHours(1)
                : OffsetDateTime.of(2031, 4, 5, 12, 0, 0, 0, ZoneOffset.UTC);
        return jdbc.sql("""
                        INSERT INTO purchase_orders (
                            number, store_id, supplier_id, status, origin, sent_at,
                            net_total, tax_total, total, created_at
                        )
                        VALUES (:num, :s, :sup, :st, 'MANUAL', :sentAt, :net, 0, :net, :createdAt)
                        RETURNING id
                        """)
                .param("num", "P85O-" + sfx + "-" + (++seq))
                .param("s", storeId).param("sup", supplierId).param("st", status)
                .param("sentAt", sentAt, Types.TIMESTAMP_WITH_TIMEZONE).param("net", netTotal).param("createdAt", createdAt)
                .query(Long.class).single();
    }

    private void insertPoLine(long poId, String qtyOrdered, String qtyReceived, String unitCost, long lineNet) {
        BigDecimal ordered = new BigDecimal(qtyOrdered);
        jdbc.sql("""
                        INSERT INTO purchase_order_lines (
                            purchase_order_id, product_id, qty_purchase_units, units_per_purchase_unit,
                            qty_ordered, unit_cost_net, qty_received, line_net_total
                        )
                        VALUES (:poId, :p, :qty, 1.000, :qty, :cost, :rec, :net)
                        """)
                .param("poId", poId).param("p", productId).param("qty", ordered)
                .param("cost", new BigDecimal(unitCost)).param("rec", new BigDecimal(qtyReceived))
                .param("net", lineNet)
                .update();
    }

    private void insertReceipt(long storeId, long supplierId, Long poId, String status,
                               OffsetDateTime receivedAt, long netTotal) {
        jdbc.sql("""
                        INSERT INTO goods_receipts (
                            number, purchase_order_id, store_id, supplier_id, status,
                            supplier_document_type, supplier_document_number, received_at, received_by, net_total
                        )
                        VALUES (:num, :poId, :s, :sup, :st, 'INVOICE', :docNum, :recAt, :u, :net)
                        """)
                .param("num", "P85R-" + sfx + "-" + (++seq))
                .param("poId", poId, Types.BIGINT).param("s", storeId).param("sup", supplierId).param("st", status)
                .param("docNum", "FAC-" + seq).param("recAt", receivedAt).param("u", userId).param("net", netTotal)
                .update();
    }
}
