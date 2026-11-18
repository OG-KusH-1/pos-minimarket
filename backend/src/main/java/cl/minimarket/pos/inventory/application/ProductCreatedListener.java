package cl.minimarket.pos.inventory.application;

import cl.minimarket.pos.catalog.ProductCreatedEvent;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.organization.StoreApi;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Escucha {@link ProductCreatedEvent} para inicializar en cero las filas de {@code stock} en cada
 * local activo (PLAN A.4, RN-04).
 *
 * <p>Los productos de tipo {@link SaleType#PACK} no mantienen existencias propias (RN-05), por lo
 * que se omiten.
 */
@Component
public class ProductCreatedListener {

    private final StoreApi storeApi;
    private final JdbcClient jdbc;

    public ProductCreatedListener(StoreApi storeApi, JdbcClient jdbc) {
        this.storeApi = Objects.requireNonNull(storeApi, "storeApi");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Crea una fila en {@code stock} con cantidad {@code 0} para cada local activo dentro de la
     * misma transacción de creación del producto.
     *
     * @param event evento emitido por {@code catalog} al crear un producto
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProductCreated(ProductCreatedEvent event) {
        if (event.saleType() == SaleType.PACK) {
            return;
        }
        List<Long> storeIds = storeApi.activeStoreIds();
        for (Long storeId : storeIds) {
            jdbc.sql("""
                    INSERT INTO stock (store_id, product_id, quantity)
                    VALUES (:storeId, :productId, 0)
                    ON CONFLICT (store_id, product_id) DO NOTHING
                    """)
                    .param("storeId", storeId)
                    .param("productId", event.productId())
                    .update();
        }
    }
}
