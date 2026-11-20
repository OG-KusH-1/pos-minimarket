package cl.minimarket.pos.purchasing.order.application;

import cl.minimarket.pos.purchasing.PurchaseOrderApi;
import cl.minimarket.pos.purchasing.PurchaseOrderApi.DraftLineCommand;
import cl.minimarket.pos.purchasing.PurchaseOrderApi.DraftOrderCommand;
import cl.minimarket.pos.purchasing.PurchaseOrderOrigin;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de la API pública {@link PurchaseOrderApi#createDrafts}
 * (preparación de {@code T80}: generación de borradores de órdenes de compra por proveedor
 * desde una corrida de reabastecimiento, {@code PLAN.md} A.4 y C.5).
 *
 * <p>Llama la API en frío (sin request HTTP): el local y el origen vienen en el propio
 * comando, no en el {@code StoreContext}, que es justo el contrato que usa
 * {@code replenishment}.
 */
class PurchaseOrderApiIT extends AbstractIntegrationIT {

    private static final AtomicLong RUT_SEQ = new AtomicLong(
            96_000_000L + (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 3_000_000L));

    @Autowired
    PurchaseOrderApi purchaseOrderApi;

    private String suffix;
    private long storeId;
    private long supplierAId;
    private long supplierBId;
    private long productAId;
    private long productBId;
    private long productSupplierAId;
    private long runId;
    private long replenishmentLineAId;

    @BeforeEach
    void setUpData() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        storeId = insertStore("RDA" + suffix.substring(0, 6).toUpperCase(), "Local Reabasto " + suffix);
        long categoryId = insertCategory("Reabasto " + suffix);
        // productA lleva IVA; productB es exento (así se ejercitan impuesto y exención en los totales)
        productAId = insertProduct("SKU-RDA-" + suffix, "Bebida 1,5L " + suffix, categoryId, false);
        productBId = insertProduct("SKU-RDB-" + suffix, "Pan Exento " + suffix, categoryId, true);

        Long vatTaxId = appJdbc().sql("SELECT id FROM taxes WHERE tax_type = 'VAT' AND active = true ORDER BY id LIMIT 1")
                .query(Long.class).optional().orElse(null);
        if (vatTaxId != null) {
            appJdbc().sql("INSERT INTO product_taxes (product_id, tax_id) VALUES (:p, :t)")
                    .param("p", productAId)
                    .param("t", vatTaxId)
                    .update();
        }

        supplierAId = insertSupplier(nextValidRut(), "Distribuidora A " + suffix, "compras-a-" + suffix + "@proveedor.cl");
        supplierBId = insertSupplier(nextValidRut(), "Distribuidora B " + suffix, "compras-b-" + suffix + "@proveedor.cl");
        // Caja de 10 unidades a $10.000 netos por caja -> costo unitario neto por unidad base = $1.000
        productSupplierAId = insertProductSupplier(productAId, supplierAId, new BigDecimal("10"), new BigDecimal("10000"));

        // Corrida completada y una línea SUGGESTED de productA: los borradores las referencian
        // (FK de purchase_orders.replenishment_run_id y purchase_order_lines.replenishment_line_id)
        runId = appJdbc().sql("""
                        INSERT INTO replenishment_runs (store_id, as_of_date, status, params_snapshot, lines_count, started_at, finished_at)
                        VALUES (:storeId, now()::date, 'COMPLETED', '{}'::jsonb, 1, now(), now())
                        RETURNING id
                        """)
                .param("storeId", storeId)
                .query(Long.class)
                .single();
        replenishmentLineAId = appJdbc().sql("""
                        INSERT INTO replenishment_lines (run_id, product_id, supplier_id, product_supplier_id,
                                valid_days, censored_days, avg_daily_demand, demand_std, lead_time_days,
                                review_period_days, on_hand, in_transit, safety_stock, reorder_point,
                                target_level, suggested_qty, suggested_purchase_units, status)
                        VALUES (:runId, :productId, :supplierId, :productSupplierId,
                                28, 2, 10, 3, 3, 7, 25, 12, 10, 40, 110, 72, 6, 'SUGGESTED')
                        RETURNING id
                        """)
                .param("runId", runId)
                .param("productId", productAId)
                .param("supplierId", supplierAId)
                .param("productSupplierId", productSupplierAId)
                .query(Long.class)
                .single();
    }

    /**
     * Caso central de T80: dos comandos de proveedores distintos generan dos OC en estado
     * {@code DRAFT} con {@code origin=REPLENISHMENT} en una sola llamada (y una sola
     * transacción), devolviendo sus ids en el mismo orden.
     */
    @Test
    void createDraftsCreatesOneDraftOrderPerSupplierWithReplenishmentOrigin() {
        LocalDate expectedDateA = LocalDate.now().plusDays(5);

        // Proveedor A: dos líneas. productA resuelve el costo desde su asociación
        // producto-proveedor ($1.000 por unidad base, caja de 10) y enlaza la línea de
        // sugerencia; productB no tiene asociación con A, así que el costo viene explícito.
        DraftOrderCommand commandA = new DraftOrderCommand(
                storeId,
                supplierAId,
                PurchaseOrderOrigin.REPLENISHMENT,
                runId,
                expectedDateA,
                "Borrador desde corrida " + suffix,
                List.of(
                        new DraftLineCommand(productAId, new BigDecimal("2"), null, replenishmentLineAId),
                        new DraftLineCommand(productBId, new BigDecimal("5"), new BigDecimal("500"), null)));
        // Proveedor B: una sola línea, sin fecha esperada ni notas.
        DraftOrderCommand commandB = new DraftOrderCommand(
                storeId,
                supplierBId,
                PurchaseOrderOrigin.REPLENISHMENT,
                runId,
                null,
                null,
                List.of(new DraftLineCommand(productBId, new BigDecimal("3"), new BigDecimal("450"), null)));

        List<Long> ids = purchaseOrderApi.createDrafts(List.of(commandA, commandB));

        // Devuelve un id por comando, en el mismo orden, todos distintos
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isPositive();
        assertThat(ids.get(1)).isPositive();
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));

        // Orden del proveedor A: 2 cajas x 10 = 20 unid. x $1.000 = $20.000 neto (+ IVA $3.800)
        // y 5 unid. x $500 = $2.500 exento -> neto $22.500, IVA $3.800, total $26.300
        OrderRow orderA = loadOrder(ids.get(0));
        assertThat(orderA.storeId()).isEqualTo(storeId);
        assertThat(orderA.supplierId()).isEqualTo(supplierAId);
        assertThat(orderA.status()).isEqualTo("DRAFT");
        assertThat(orderA.origin()).isEqualTo("REPLENISHMENT");
        assertThat(orderA.replenishmentRunId()).isEqualTo(runId);
        assertThat(orderA.expectedDate()).isEqualTo(expectedDateA);
        assertThat(orderA.notes()).isEqualTo("Borrador desde corrida " + suffix);
        assertThat(orderA.netTotal()).isEqualByComparingTo("22500");
        assertThat(orderA.taxTotal()).isEqualByComparingTo("3800");
        assertThat(orderA.total()).isEqualByComparingTo("26300");
        assertThat(orderA.number()).isNotBlank();

        List<LineRow> linesA = loadLines(ids.get(0));
        assertThat(linesA).hasSize(2);

        LineRow lineProductA = linesA.stream().filter(l -> l.productId() == productAId).findFirst().orElseThrow();
        assertThat(lineProductA.productSupplierId()).isEqualTo(productSupplierAId);
        assertThat(lineProductA.qtyPurchaseUnits()).isEqualByComparingTo("2");
        assertThat(lineProductA.qtyOrdered()).isEqualByComparingTo("20");
        assertThat(lineProductA.unitCostNet()).isEqualByComparingTo("1000");
        assertThat(lineProductA.lineNetTotal()).isEqualByComparingTo("20000");
        assertThat(lineProductA.replenishmentLineId()).isEqualTo(replenishmentLineAId);

        LineRow lineProductB = linesA.stream().filter(l -> l.productId() == productBId).findFirst().orElseThrow();
        assertThat(lineProductB.productSupplierId()).isNull();
        assertThat(lineProductB.qtyPurchaseUnits()).isEqualByComparingTo("5");
        assertThat(lineProductB.qtyOrdered()).isEqualByComparingTo("5");
        assertThat(lineProductB.unitCostNet()).isEqualByComparingTo("500");
        assertThat(lineProductB.lineNetTotal()).isEqualByComparingTo("2500");
        assertThat(lineProductB.replenishmentLineId()).isNull();

        // Orden del proveedor B: 3 unid. x $450 = $1.350 exento, sin fecha ni notas
        OrderRow orderB = loadOrder(ids.get(1));
        assertThat(orderB.supplierId()).isEqualTo(supplierBId);
        assertThat(orderB.storeId()).isEqualTo(storeId);
        assertThat(orderB.status()).isEqualTo("DRAFT");
        assertThat(orderB.origin()).isEqualTo("REPLENISHMENT");
        assertThat(orderB.replenishmentRunId()).isEqualTo(runId);
        assertThat(orderB.expectedDate()).isNull();
        assertThat(orderB.notes()).isNull();
        assertThat(orderB.netTotal()).isEqualByComparingTo("1350");
        assertThat(orderB.taxTotal()).isEqualByComparingTo("0");
        assertThat(orderB.total()).isEqualByComparingTo("1350");
        assertThat(orderB.number()).isNotBlank();
        assertThat(orderB.number()).isNotEqualTo(orderA.number());

        List<LineRow> linesB = loadLines(ids.get(1));
        assertThat(linesB).hasSize(1);
        assertThat(linesB.get(0).productId()).isEqualTo(productBId);
        assertThat(linesB.get(0).qtyPurchaseUnits()).isEqualByComparingTo("3");
        assertThat(linesB.get(0).qtyOrdered()).isEqualByComparingTo("3");
        assertThat(linesB.get(0).unitCostNet()).isEqualByComparingTo("450");
        assertThat(linesB.get(0).lineNetTotal()).isEqualByComparingTo("1350");
        assertThat(linesB.get(0).replenishmentLineId()).isNull();
    }

    /** Guarda de la propia API: una tanda vacía se rechaza de inmediato con VALIDATION_ERROR. */
    @Test
    void createDraftsWithEmptyCommandsIsRejected() {
        assertThatThrownBy(() -> purchaseOrderApi.createDrafts(List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    /** Fila de {@code purchase_orders} leída por id (aserciones relativas: solo la fila creada). */
    private record OrderRow(Long storeId,
                            Long supplierId,
                            String status,
                            String origin,
                            Long replenishmentRunId,
                            LocalDate expectedDate,
                            String notes,
                            BigDecimal netTotal,
                            BigDecimal taxTotal,
                            BigDecimal total,
                            String number) {
    }

    /** Fila de {@code purchase_order_lines} leída por orden. */
    private record LineRow(Long productId,
                           Long productSupplierId,
                           BigDecimal qtyPurchaseUnits,
                           BigDecimal qtyOrdered,
                           BigDecimal unitCostNet,
                           BigDecimal lineNetTotal,
                           Long replenishmentLineId) {
    }

    private OrderRow loadOrder(long orderId) {
        return appJdbc().sql("""
                        SELECT store_id, supplier_id, status, origin, replenishment_run_id,
                               expected_date, notes, net_total, tax_total, total, number
                        FROM purchase_orders
                        WHERE id = :id
                        """)
                .param("id", orderId)
                .query((rs, rowNum) -> new OrderRow(
                        rs.getLong("store_id"),
                        rs.getLong("supplier_id"),
                        rs.getString("status"),
                        rs.getString("origin"),
                        (Long) rs.getObject("replenishment_run_id"),
                        rs.getDate("expected_date") != null ? rs.getDate("expected_date").toLocalDate() : null,
                        rs.getString("notes"),
                        rs.getBigDecimal("net_total"),
                        rs.getBigDecimal("tax_total"),
                        rs.getBigDecimal("total"),
                        rs.getString("number")))
                .single();
    }

    private List<LineRow> loadLines(long orderId) {
        return appJdbc().sql("""
                        SELECT product_id, product_supplier_id, qty_purchase_units, qty_ordered,
                               unit_cost_net, line_net_total, replenishment_line_id
                        FROM purchase_order_lines
                        WHERE purchase_order_id = :orderId
                        ORDER BY id ASC
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new LineRow(
                        rs.getLong("product_id"),
                        (Long) rs.getObject("product_supplier_id"),
                        rs.getBigDecimal("qty_purchase_units"),
                        rs.getBigDecimal("qty_ordered"),
                        rs.getBigDecimal("unit_cost_net"),
                        rs.getBigDecimal("line_net_total"),
                        (Long) rs.getObject("replenishment_line_id")))
                .list();
    }

    private long insertStore(String code, String name) {
        Long companyId = ownerJdbc().sql("SELECT id FROM company ORDER BY id ASC LIMIT 1")
                .query(Long.class)
                .optional()
                .orElseGet(() -> ownerJdbc().sql("""
                        INSERT INTO company (rut, legal_name, trade_name, business_activity,
                                             address, commune, city, timezone, currency)
                        VALUES ('76123456-7', 'Minimarket Test SpA', 'Minimarket Test', 'Minimarket',
                                'Av. Test 100', 'Santiago', 'Santiago', 'America/Santiago', 'CLP')
                        RETURNING id
                        """)
                        .query(Long.class)
                        .single());
        return appJdbc().sql("""
                        INSERT INTO stores (company_id, code, name, address, commune, city, active)
                        VALUES (:companyId, :code, :name, 'Av. Test 100', 'Santiago', 'Santiago', true)
                        RETURNING id
                        """)
                .param("companyId", companyId)
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertCategory(String name) {
        return ownerJdbc().sql("""
                        INSERT INTO categories (name, active, created_at, updated_at, version)
                        VALUES (:name, true, now(), now(), 0)
                        RETURNING id
                        """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertProduct(String sku, String name, long categoryId, boolean vatExempt) {
        return appJdbc().sql("""
                        INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                              price_gross, avg_cost, last_cost, vat_exempt, active)
                        VALUES (:sku, :name, :catId, 'UNIT', 'UNIT', 2000, 1000, 1000, :vatExempt, true)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("catId", categoryId)
                .param("vatExempt", vatExempt)
                .query(Long.class)
                .single();
    }

    private long insertSupplier(String rut, String legalName, String email) {
        return appJdbc().sql("""
                        INSERT INTO suppliers (rut, legal_name, email, payment_terms_type, payment_terms_days,
                                               lead_time_days, delivery_weekdays, active)
                        VALUES (:rut, :legalName, :email, 'CREDIT', 30, 2, ARRAY[1,3]::smallint[], true)
                        RETURNING id
                        """)
                .param("rut", rut)
                .param("legalName", legalName)
                .param("email", email)
                .query(Long.class)
                .single();
    }

    private long insertProductSupplier(long productId, long supplierId,
                                       BigDecimal unitsPerPurchaseUnit, BigDecimal costPerPurchaseUnit) {
        return appJdbc().sql("""
                        INSERT INTO product_suppliers (product_id, supplier_id, purchase_unit_name,
                                                       units_per_purchase_unit, cost_per_purchase_unit,
                                                       min_order_qty, is_preferred, active)
                        VALUES (:pid, :sid, 'Caja', :uppu, :cpu, 1, true, true)
                        RETURNING id
                        """)
                .param("pid", productId)
                .param("sid", supplierId)
                .param("uppu", unitsPerPurchaseUnit)
                .param("cpu", costPerPurchaseUnit)
                .query(Long.class)
                .single();
    }

    private static String nextValidRut() {
        long body = RUT_SEQ.incrementAndGet();
        return body + "-" + computeCheckDigit(body);
    }

    private static char computeCheckDigit(long body) {
        long n = body;
        int sum = 0;
        int factor = 2;
        while (n > 0) {
            sum += (int) ((n % 10) * factor);
            n /= 10;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int rem = 11 - (sum % 11);
        if (rem == 11) {
            return '0';
        }
        if (rem == 10) {
            return 'K';
        }
        return (char) ('0' + rem);
    }
}
