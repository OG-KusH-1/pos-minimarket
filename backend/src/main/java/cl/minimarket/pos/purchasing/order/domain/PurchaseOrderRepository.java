package cl.minimarket.pos.purchasing.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA de órdenes de compra ({@code purchase_orders}).
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByIdAndStoreId(Long id, Long storeId);

    @Query("SELECT o FROM PurchaseOrder o LEFT JOIN FETCH o.lines WHERE o.id = :id AND o.storeId = :storeId")
    Optional<PurchaseOrder> findWithLinesByIdAndStoreId(@Param("id") Long id, @Param("storeId") Long storeId);

    /**
     * Devuelve los identificadores de líneas de reabastecimiento (no nulos) que ya están en alguna
     * orden de compra; lo consume {@code PurchaseOrderApiImpl.replenishmentLineIdsInOrders} (T80).
     */
    @Query("SELECT DISTINCT l.replenishmentLineId FROM PurchaseOrder o JOIN o.lines l "
            + "WHERE l.replenishmentLineId IN :ids")
    List<Long> findUsedReplenishmentLineIds(@Param("ids") Collection<Long> ids);
}
