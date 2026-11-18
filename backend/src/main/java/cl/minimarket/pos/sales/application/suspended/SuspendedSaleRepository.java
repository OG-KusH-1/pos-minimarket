package cl.minimarket.pos.sales.application.suspended;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA para ventas suspendidas ({@code suspended_sales}).
 */
public interface SuspendedSaleRepository extends JpaRepository<SuspendedSale, Long> {

    /**
     * Lista las ventas suspendidas vigentes de una terminal ordenadas desde la más antigua.
     *
     * @param storeId identificador del local
     * @param terminalId identificador de la terminal
     * @param now instante actual para filtrar las aún no expiradas
     * @return lista ordenada ascendentemente por fecha de creación
     */
    List<SuspendedSale> findByStoreIdAndTerminalIdAndExpiresAtAfterOrderByCreatedAtAsc(
            Long storeId,
            Long terminalId,
            Instant now
    );

    /**
     * Cuenta las ventas suspendidas vigentes en la terminal indicada.
     *
     * @param storeId identificador del local
     * @param terminalId identificador de la terminal
     * @param now instante actual
     * @return cantidad de ventas suspendidas no expiradas
     */
    long countByStoreIdAndTerminalIdAndExpiresAtAfter(Long storeId, Long terminalId, Instant now);

    /**
     * Obtiene una venta suspendida por id, local y terminal aplicando bloqueo pesimista de escritura
     * para evitar retomas o eliminaciones concurrentes duplicadas.
     *
     * @param id identificador de la venta suspendida
     * @param storeId identificador del local activo
     * @param terminalId identificador de la terminal activa
     * @return la venta si pertenece al local y terminal indicados
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SuspendedSale s where s.id = :id and s.storeId = :storeId and s.terminalId = :terminalId")
    Optional<SuspendedSale> findByIdAndStoreIdAndTerminalIdForUpdate(
            @Param("id") Long id,
            @Param("storeId") Long storeId,
            @Param("terminalId") Long terminalId
    );
}
