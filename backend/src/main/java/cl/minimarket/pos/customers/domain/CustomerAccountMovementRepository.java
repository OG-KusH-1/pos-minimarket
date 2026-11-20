package cl.minimarket.pos.customers.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio JPA de movimientos de cuenta corriente ({@code customer_account_movements}).
 */
public interface CustomerAccountMovementRepository extends JpaRepository<CustomerAccountMovement, Long> {

    Optional<CustomerAccountMovement> findByIdempotencyKey(UUID idempotencyKey);
}
