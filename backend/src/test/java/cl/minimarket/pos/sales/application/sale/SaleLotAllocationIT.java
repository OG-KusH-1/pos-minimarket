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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba de integración que verifica la persistencia de {@code sale_lot_allocations} durante
 * {@link SaleService#create} bajo consumo FEFO (incluyendo mezcla de producto base y {@code PACK}).
 */
class SaleLotAllocationIT extends AbstractIntegrationIT {

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

    @Autowired
    BusinessClock clock;

    private String suffix;
    private long storeId;
    private long cashierId;
    private long terminalId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        cashierId = insertCashier("cajlot_" + suffix);
        terminalId = insertTerminal(storeId, "CAJ-L-" + suffix);
        openCashSession(storeId, terminalId, cashierId);
        categoryId = ownerJdbc().sql("INSERT INTO categories (name, active) VALUES (:n, true) RETURNING id")
                .param("n", "CatLot-" + suffix)
                .query(Long.class)
                .single();
    }

    @Test
    void salePersistsFefoLotAllocationsAcrossUnitAndPackLines() throws Exception {
        long baseProductId = createProduct("BASEL-" + suffix, "Yogurt Natural", "UNIT", 1000,
                new BigDecimal("600.0000"), true, null);
        long packProductId = createProduct("PACKL-" + suffix, "Pack 6 Yogurts", "PACK", 5500,
                new BigDecimal("3600.0000"), false, baseProductId);

        ownerJdbc().sql("""
                INSERT INTO stock (store_id, product_id, quantity)
                VALUES (:s, :p, 0)
                ON CONFLICT (store_id, product_id) DO NOTHING
                """)
                .param("s", storeId)
                .param("p", baseProductId)
                .update();

        LocalDate today = clock.businessDate();
        // Cargar Lot A (vence antes, qty=4) y Lot B (vence después, qty=10)
        long lotAId = tx.execute(status -> stockApi.apply(new StockCommand(
                storeId,
                StockMovementType.PURCHASE_RECEIPT,
                StockSourceType.GOODS_RECEIPT,
                1L,
                cashierId,
                "Carga lote A",
                List.of(new StockLine(baseProductId, new BigDecimal("4"), new BigDecimal("600"), null,
                        null, "LOT-A", today.plusMonths(2))))))
                .getFirst().allocations().getFirst().lotId();

        long lotBId = tx.execute(status -> stockApi.apply(new StockCommand(
                storeId,
                StockMovementType.PURCHASE_RECEIPT,
                StockSourceType.GOODS_RECEIPT,
                2L,
                cashierId,
                "Carga lote B",
                List.of(new StockLine(baseProductId, new BigDecimal("10"), new BigDecimal("600"), null,
                        null, "LOT-B", today.plusMonths(5))))))
                .getFirst().allocations().getFirst().lotId();

        // Vender 2 unidades base + 1 pack de 6 = 8 unidades base totales -> Lot A queda en 0 (DEPLETED), Lot B en 6.
        MvcResult res = mvc.perform(post("/api/v1/sales")
                        .header("X-Store-Id", storeId)
                        .header("X-Terminal-Id", terminalId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    { "productId": %d, "quantity": 2 },
                                    { "productId": %d, "quantity": 1 }
                                  ],
                                  "payments": [
                                    { "method": "CASH", "tenderedAmount": 8000 }
                                  ]
                                }
                                """.formatted(baseProductId, packProductId))
                        .with(cashierAuth(cashierId)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = jsonMapper.readTree(res.getResponse().getContentAsString());
        long saleId = body.get("id").asLong();

        // Verificar suma de asignaciones por línea de venta == stock_quantity.
        List<LineAllocationSummary> summaries = ownerJdbc().sql("""
                SELECT sl.id AS sale_line_id,
                       sl.stock_quantity,
                       COALESCE(SUM(sla.quantity), 0) AS allocated_sum
                FROM sale_lines sl
                LEFT JOIN sale_lot_allocations sla ON sla.sale_line_id = sl.id
                WHERE sl.sale_id = :saleId
                GROUP BY sl.id, sl.stock_quantity
                ORDER BY sl.line_number ASC
                """)
                .param("saleId", saleId)
                .query((rs, rowNum) -> new LineAllocationSummary(
                        rs.getLong("sale_line_id"),
                        rs.getBigDecimal("stock_quantity"),
                        rs.getBigDecimal("allocated_sum")))
                .list();

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).stockQuantity()).isEqualByComparingTo("2.000");
        assertThat(summaries.get(0).allocatedSum()).isEqualByComparingTo("2.000");
        assertThat(summaries.get(1).stockQuantity()).isEqualByComparingTo("6.000");
        assertThat(summaries.get(1).allocatedSum()).isEqualByComparingTo("6.000");

        // Total asignado al Lot A = 4.000 y al Lot B = 4.000
        BigDecimal lotAAllocated = ownerJdbc().sql("""
                SELECT COALESCE(SUM(sla.quantity), 0)
                FROM sale_lot_allocations sla
                JOIN sale_lines sl ON sl.id = sla.sale_line_id
                WHERE sl.sale_id = :saleId AND sla.lot_id = :lotId
                """)
                .param("saleId", saleId).param("lotId", lotAId).query(BigDecimal.class).single();
        BigDecimal lotBAllocated = ownerJdbc().sql("""
                SELECT COALESCE(SUM(sla.quantity), 0)
                FROM sale_lot_allocations sla
                JOIN sale_lines sl ON sl.id = sla.sale_line_id
                WHERE sl.sale_id = :saleId AND sla.lot_id = :lotId
                """)
                .param("saleId", saleId).param("lotId", lotBId).query(BigDecimal.class).single();

        assertThat(lotAAllocated).isEqualByComparingTo("4.000");
        assertThat(lotBAllocated).isEqualByComparingTo("4.000");

        // Invariante RN-04 post venta: Lot A = 0 DEPLETED, Lot B = 6 ACTIVE, Stock = 6.
        assertThat(ownerJdbc().sql("SELECT status FROM lots WHERE id = :id").param("id", lotAId).query(String.class).single())
                .isEqualTo("DEPLETED");
        assertThat(ownerJdbc().sql("SELECT quantity FROM lots WHERE id = :id").param("id", lotBId).query(BigDecimal.class).single())
                .isEqualByComparingTo("6.000");
        assertThat(stockApi.onHand(storeId, baseProductId)).isEqualByComparingTo("6.000");
    }

    private RequestPostProcessor cashierAuth(long uid) {
        return user(String.valueOf(uid)).authorities(
                new SimpleGrantedAuthority(Permissions.SALES_POS_OPERATE),
                new SimpleGrantedAuthority(Permissions.SALES_SALE_READ),
                new SimpleGrantedAuthority(Permissions.SALES_DISCOUNT_APPLY)
        );
    }

    private long insertCashier(String username) {
        long uid = ownerJdbc().sql("""
                INSERT INTO users (username, full_name, password_hash, pin_hash)
                VALUES (:u, 'Cajero Lotes', 'x', :pin)
                RETURNING id
                """)
                .param("u", username)
                .param("pin", passwordEncoder.encode("1234"))
                .query(Long.class)
                .single();
        ownerJdbc().sql("INSERT INTO user_stores (user_id, store_id) VALUES (:u, :s) ON CONFLICT DO NOTHING")
                .param("u", uid).param("s", storeId).update();
        long roleId = ownerJdbc().sql("SELECT id FROM roles WHERE code = 'CASHIER'")
                .query(Long.class).single();
        ownerJdbc().sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r) ON CONFLICT DO NOTHING")
                .param("u", uid).param("r", roleId).update();
        return uid;
    }

    private long insertTerminal(long store, String code) {
        return ownerJdbc().sql("""
                INSERT INTO terminals (store_id, code, name, active)
                VALUES (:s, :c, :n, true)
                RETURNING id
                """)
                .param("s", store).param("c", code).param("n", "Caja " + code).query(Long.class).single();
    }

    private void openCashSession(long store, long terminal, long cashier) {
        String num = "TU-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ownerJdbc().sql("""
                INSERT INTO cash_sessions (number, store_id, terminal_id, cashier_user_id, status, business_date, opening_amount)
                VALUES (:num, :s, :t, :c, 'OPEN', :businessDate, 50000)
                """)
                .param("num", num)
                .param("s", store)
                .param("t", terminal)
                .param("c", cashier)
                .param("businessDate", clock.businessDate())
                .update();
    }

    private long createProduct(
            String sku, String name, String saleType, long priceGross,
            BigDecimal avgCost, boolean lotControlled, Long baseProductId) {
        long productId = ownerJdbc().sql("""
                INSERT INTO products (
                    sku, name, category_id, sale_type, unit_of_measure,
                    price_gross, avg_cost, vat_exempt, lot_controlled, active, allow_manual_discount
                )
                VALUES (:sku, :name, :cat, :st, 'UNIT', :price, :cost, false, :lc, true, true)
                RETURNING id
                """)
                .param("sku", sku).param("name", name).param("cat", categoryId)
                .param("st", saleType).param("price", priceGross).param("cost", avgCost)
                .param("lc", lotControlled)
                .query(Long.class)
                .single();

        long ivaId = ownerJdbc().sql("SELECT id FROM taxes WHERE code = 'IVA'").query(Long.class).single();
        ownerJdbc().sql("INSERT INTO product_taxes (product_id, tax_id) VALUES (:p, :t)")
                .param("p", productId).param("t", ivaId).update();

        if ("PACK".equals(saleType) && baseProductId != null) {
            ownerJdbc().sql("INSERT INTO product_packs (product_id, base_product_id, factor) VALUES (:p, :b, 6)")
                    .param("p", productId).param("b", baseProductId).update();
        }
        return productId;
    }

    private record LineAllocationSummary(
            long saleLineId,
            BigDecimal stockQuantity,
            BigDecimal allocatedSum) {
    }
}
