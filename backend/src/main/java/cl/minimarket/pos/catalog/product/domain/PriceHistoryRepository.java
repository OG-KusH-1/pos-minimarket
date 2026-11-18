package cl.minimarket.pos.catalog.product.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio JPA del historial de precios y costos ({@code price_history}).
 */
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    /** Lista paginada del historial de un producto. */
    Page<PriceHistory> findByProductId(Long productId, Pageable pageable);
}
