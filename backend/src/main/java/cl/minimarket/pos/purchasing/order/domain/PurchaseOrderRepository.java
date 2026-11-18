package cl.minimarket.pos.purchasing.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repositorio JPA de órdenes de compra ({@code purchase_orders}).
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByIdAndStoreId(Long id, Long storeId);

    @Query("SELECT o FROM PurchaseOrder o LEFT JOIN FETCH o.lines WHERE o.id = :id AND o.storeId = :storeId")
    Optional<PurchaseOrder> findWithLinesByIdAndStoreId(@Param("id") Long id, @Param("storeId") Long storeId);
}
