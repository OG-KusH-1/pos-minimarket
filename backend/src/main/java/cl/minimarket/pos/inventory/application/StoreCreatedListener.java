package cl.minimarket.pos.inventory.application;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.organization.StoreCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Escucha {@link StoreCreatedEvent} para inicializar en cero las filas de {@code stock} de todos
 * los productos no {@code PACK} existentes en el catálogo (PLAN A.4, RN-04).
 *
 * <p>Los productos de tipo {@code PACK} no mantienen existencias propias (RN-05), por lo que se
 * excluyen mediante {@link ProductCatalogApi#nonPackProductIds()}.
 */
@Component
public class StoreCreatedListener {

    private final ProductCatalogApi productCatalogApi;
    private final JdbcClient jdbc;

    public StoreCreatedListener(ProductCatalogApi productCatalogApi, JdbcClient jdbc) {
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Crea una fila en {@code stock} con cantidad {@code 0} para cada producto no {@code PACK}
     * dentro de la misma transacción de creación del local.
     *
     * @param event evento emitido por {@code organization} al crear un local
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStoreCreated(StoreCreatedEvent event) {
        List<Long> productIds = productCatalogApi.nonPackProductIds();
        for (Long productId : productIds) {
            jdbc.sql("""
                    INSERT INTO stock (store_id, product_id, quantity)
                    VALUES (:storeId, :productId, 0)
                    ON CONFLICT (store_id, product_id) DO NOTHING
                    """)
                    .param("storeId", event.storeId())
                    .param("productId", productId)
                    .update();
        }
    }
}
