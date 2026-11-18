package cl.minimarket.pos.inventory.application;

import cl.minimarket.pos.organization.StoreCreatedEvent;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de integración de {@link StoreCreatedListener} (T24, RN-04, RN-05).
 */
class StoreCreatedListenerIT extends AbstractIntegrationIT {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Test
    void onStoreCreated_createsZeroStockOnlyForNonPackProducts() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long categoryId = insertCategory("Cat-" + suffix);
        long unitProductId = insertProduct("SKU-U-" + suffix, "Prod Unit " + suffix, categoryId, "UNIT", "UNIT");
        long weightProductId = insertProduct("SKU-W-" + suffix, "Prod Weight " + suffix, categoryId, "WEIGHT", "KG");
        long packProductId = insertProduct("SKU-P-" + suffix, "Prod Pack " + suffix, categoryId, "PACK", "UNIT");

        String storeCode = TestData.uniqueStoreCode();
        long newStoreId = TestData.insertStore(ownerJdbc(), storeCode);

        tx.executeWithoutResult(status ->
                events.publishEvent(new StoreCreatedEvent(newStoreId, storeCode, "Local " + storeCode)));

        BigDecimal unitQty = queryStockQty(newStoreId, unitProductId);
        BigDecimal weightQty = queryStockQty(newStoreId, weightProductId);
        Long packRows = ownerJdbc()
                .sql("SELECT COUNT(*) FROM stock WHERE store_id = :storeId AND product_id = :productId")
                .param("storeId", newStoreId)
                .param("productId", packProductId)
                .query(Long.class)
                .single();

        assertThat(unitQty).isEqualByComparingTo("0.000");
        assertThat(weightQty).isEqualByComparingTo("0.000");
        assertThat(packRows).isZero();
    }

    private BigDecimal queryStockQty(long storeId, long productId) {
        return ownerJdbc()
                .sql("SELECT quantity FROM stock WHERE store_id = :storeId AND product_id = :productId")
                .param("storeId", storeId)
                .param("productId", productId)
                .query(BigDecimal.class)
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

    private long insertProduct(String sku, String name, long categoryId, String saleType, String uom) {
        return ownerJdbc().sql("""
                INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                      price_gross, avg_cost, vat_exempt, restricted_type,
                                      lot_controlled, active, created_at, updated_at, version)
                VALUES (:sku, :name, :categoryId, :saleType, :uom,
                        1000, 500.0000, false, NULL,
                        false, true, now(), now(), 0)
                RETURNING id
                """)
                .param("sku", sku)
                .param("name", name)
                .param("categoryId", categoryId)
                .param("saleType", saleType)
                .param("uom", uom)
                .query(Long.class)
                .single();
    }
}
