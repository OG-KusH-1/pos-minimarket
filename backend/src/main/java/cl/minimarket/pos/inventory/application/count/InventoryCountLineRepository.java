package cl.minimarket.pos.inventory.application.count;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Repositorio JPA para las líneas {@link InventoryCountLine} de una toma de inventario.
 */
public interface InventoryCountLineRepository extends JpaRepository<InventoryCountLine, Long> {

    List<InventoryCountLine> findByCountIdOrderByIdAsc(Long countId);

    List<InventoryCountLine> findByCountIdAndIdIn(Long countId, Collection<Long> ids);

    long countByCountIdAndCountedAtIsNull(Long countId);

    long countByCountId(Long countId);

    boolean existsByCountIdAndCountedBy(Long countId, Long countedBy);
}
