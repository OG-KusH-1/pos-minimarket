package cl.minimarket.pos.cash.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Repositorio JPA de arqueos por medio de pago ({@code cash_session_counts}).
 */
public interface CashSessionCountRepository extends JpaRepository<CashSessionCount, Long> {

    /** Obtiene las líneas de arqueo de un turno. */
    List<CashSessionCount> findByCashSessionIdOrderByIdAsc(Long cashSessionId);

    /** Obtiene las líneas de arqueo de un conjunto de turnos en lote. */
    List<CashSessionCount> findByCashSessionIdInOrderByIdAsc(Collection<Long> cashSessionIds);
}
