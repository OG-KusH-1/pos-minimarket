package cl.minimarket.pos.notification.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositorio JPA para la tabla outbox {@code outbound_emails}.
 */
public interface OutboundEmailRepository extends JpaRepository<OutboundEmail, Long> {

    /**
     * Obtiene los correos pendientes ordenados por fecha de creación ascendente, aprovechando el
     * índice parcial {@code ix_outbound_emails_pending}.
     */
    List<OutboundEmail> findByStatusOrderByCreatedAtAsc(OutboundEmailStatus status, Pageable pageable);
}
