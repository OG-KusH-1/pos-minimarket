package cl.minimarket.pos.replenishment.application.run;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio JPA de corridas de reabastecimiento ({@code replenishment_runs}).
 */
public interface ReplenishmentRunRepository extends JpaRepository<ReplenishmentRun, Long> {

    /** Corrida del local indicado (las corridas siempre se consultan dentro de un local). */
    Optional<ReplenishmentRun> findByIdAndStoreId(Long id, Long storeId);

    /** Corridas del local, de la más reciente a la más antigua. */
    Page<ReplenishmentRun> findByStoreId(Long storeId, Pageable pageable);
}
