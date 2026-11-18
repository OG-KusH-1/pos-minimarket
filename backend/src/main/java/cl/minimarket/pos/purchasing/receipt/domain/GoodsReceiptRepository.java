package cl.minimarket.pos.purchasing.receipt.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repositorio JPA de recepciones de mercadería ({@code goods_receipts}).
 */
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    Optional<GoodsReceipt> findByIdAndStoreId(Long id, Long storeId);

    @Query("SELECT r FROM GoodsReceipt r LEFT JOIN FETCH r.lines WHERE r.id = :id AND r.storeId = :storeId")
    Optional<GoodsReceipt> findWithLinesByIdAndStoreId(@Param("id") Long id, @Param("storeId") Long storeId);
}
