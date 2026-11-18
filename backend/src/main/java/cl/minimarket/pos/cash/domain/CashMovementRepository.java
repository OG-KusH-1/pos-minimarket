package cl.minimarket.pos.cash.domain;

import cl.minimarket.pos.cash.CashMovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio JPA de movimientos de efectivo ({@code cash_movements}).
 */
public interface CashMovementRepository extends JpaRepository<CashMovement, Long> {

    /** Lista los movimientos de un turno en orden cronológico ascendente. */
    List<CashMovement> findByCashSessionIdOrderByOccurredAtAscIdAsc(Long cashSessionId);

    /** Busca un movimiento por su clave de idempotencia única (L-04). */
    Optional<CashMovement> findByIdempotencyKey(UUID idempotencyKey);

    /** Suma los montos de un turno para un tipo de movimiento dado. */
    @Query("""
            SELECT COALESCE(SUM(m.amount), 0)
            FROM CashMovement m
            WHERE m.cashSessionId = :sessionId AND m.movementType = :type
            """)
    BigDecimal sumAmountBySessionAndType(@Param("sessionId") Long sessionId,
                                         @Param("type") CashMovementType type);
}
