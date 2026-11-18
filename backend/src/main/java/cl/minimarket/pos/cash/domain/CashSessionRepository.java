package cl.minimarket.pos.cash.domain;

import cl.minimarket.pos.cash.CashSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * Repositorio JPA de turnos de caja ({@code cash_sessions}).
 */
public interface CashSessionRepository extends JpaRepository<CashSession, Long>, JpaSpecificationExecutor<CashSession> {

    /** Busca el turno abierto en una terminal específica. */
    Optional<CashSession> findByTerminalIdAndStatus(Long terminalId, CashSessionStatus status);

    /** Busca un turno perteneciente al local indicado. */
    Optional<CashSession> findByIdAndStoreId(Long id, Long storeId);

    /** Indica si una terminal tiene un turno en el estado indicado. */
    boolean existsByTerminalIdAndStatus(Long terminalId, CashSessionStatus status);

    /** Indica si un cajero tiene un turno en el estado indicado. */
    boolean existsByCashierUserIdAndStatus(Long cashierUserId, CashSessionStatus status);

    /** Indica si un local tiene al menos un turno en el estado indicado. */
    boolean existsByStoreIdAndStatus(Long storeId, CashSessionStatus status);

    /** Indica si un turno existe con un estado específico. */
    boolean existsByIdAndStatus(Long id, CashSessionStatus status);
}
