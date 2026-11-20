package cl.minimarket.pos.replenishment.application.run;

import cl.minimarket.pos.replenishment.domain.ReplenishmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA de líneas de sugerencia de reabastecimiento ({@code replenishment_lines}).
 *
 * <p>Los listados con filtros (estado, proveedor, flag, categoría) y paginación se hacen con
 * consultas nativas sobre la tabla propia en {@code ReplenishmentQueryService} (el filtro por
 * flag necesita {@code = ANY(flags)}, que JPQL no expresa sobre {@code varchar[]}).
 */
public interface ReplenishmentLineRepository extends JpaRepository<ReplenishmentLine, Long> {

    /** Líneas de una corrida, por id ascendente (orden del cálculo). */
    List<ReplenishmentLine> findByRunIdOrderByIdAsc(Long runId);

    /** Línea de una corrida concreta. */
    Optional<ReplenishmentLine> findByIdAndRunId(Long id, Long runId);

    /** Líneas seleccionadas de una corrida por sus ids, ordenadas por id. */
    List<ReplenishmentLine> findByRunIdAndIdInOrderByIdAsc(Long runId, Collection<Long> ids);

    /** Líneas de una corrida en un estado concreto que tienen proveedor asignado, ordenadas por id. */
    List<ReplenishmentLine> findByRunIdAndStatusAndSupplierIdIsNotNullOrderByIdAsc(Long runId,
                                                                                   ReplenishmentStatus status);

    /** Ids de producto con línea en la corrida (para el filtro por categoría sin SQL ajeno). */
    @Query("SELECT l.productId FROM ReplenishmentLine l WHERE l.runId = :runId")
    List<Long> findProductIdsByRunId(@Param("runId") Long runId);

    /** Líneas elegibles para generar borradores: sugeridas o aceptadas, con proveedor y unidades. */
    @Query("""
            SELECT l FROM ReplenishmentLine l
            WHERE l.runId = :runId
              AND l.status IN ('SUGGESTED', 'ACCEPTED')
              AND l.supplierId IS NOT NULL
            ORDER BY l.id ASC
            """)
    List<ReplenishmentLine> findDraftCandidates(@Param("runId") Long runId);

    /**
     * Conteo de líneas de una corrida por estado, para {@code countsByStatus} del listado de
     * corridas (una consulta por página, no una por corrida).
     */
    @Query("""
            SELECT l.runId AS runId, l.status AS status, COUNT(l) AS total
            FROM ReplenishmentLine l
            WHERE l.runId IN :runIds
            GROUP BY l.runId, l.status
            """)
    List<RunStatusCount> countByStatusForRuns(@Param("runIds") Collection<Long> runIds);

    /** Proyección del conteo anterior. */
    interface RunStatusCount {

        /** @return id de la corrida */
        Long getRunId();

        /** @return estado de las líneas */
        ReplenishmentStatus getStatus();

        /** @return cantidad de líneas en ese estado */
        long getTotal();
    }
}
