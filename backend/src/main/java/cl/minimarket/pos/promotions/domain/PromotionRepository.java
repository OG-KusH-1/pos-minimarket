package cl.minimarket.pos.promotions.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA de promociones ({@code promotions}).
 */
public interface PromotionRepository extends JpaRepository<Promotion, Long>, JpaSpecificationExecutor<Promotion> {

    /**
     * Busca una promoción por id cargando sus ítems de alcance.
     *
     * @param id identificador de la promoción
     * @return promoción encontrada o vacío
     */
    @Query("""
            SELECT DISTINCT p FROM Promotion p
            LEFT JOIN FETCH p.items
            WHERE p.id = :id
            """)
    Optional<Promotion> findWithItemsById(@Param("id") Long id);

    /**
     * Carga las promociones activas y vigentes por rango de fechas para un local dado,
     * ordenadas por prioridad ascendente e id ascendente.
     *
     * <p>La vigencia por día de la semana ({@code daysOfWeek}) y franja horaria
     * ({@code timeFrom}/{@code timeTo}) en la zona horaria del negocio se evalúa en memoria
     * con {@code BusinessClock}.
     *
     * @param storeId identificador del local
     * @param at      instante de evaluación
     * @return promociones candidatas con sus ítems cargados
     */
    @Query("""
            SELECT DISTINCT p FROM Promotion p
            LEFT JOIN FETCH p.items
            WHERE p.active = true
              AND p.validFrom <= :at
              AND (p.validTo IS NULL OR p.validTo > :at)
              AND (p.allStores = true OR :storeId MEMBER OF p.storeIds)
            ORDER BY p.priority ASC, p.id ASC
            """)
    List<Promotion> findCandidatesForStoreAt(@Param("storeId") Long storeId, @Param("at") Instant at);
}
