package cl.minimarket.pos.sales.application.sale;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio JPA de cabeceras de venta ({@code sales}).
 */
public interface SaleRepository extends JpaRepository<Sale, Long> {

    Optional<Sale> findByIdAndStoreId(Long id, Long storeId);

    Optional<Sale> findByNumberAndStoreId(String number, Long storeId);

    Optional<Sale> findByIdempotencyKey(UUID idempotencyKey);
}
