package cl.minimarket.pos.inventory.application.count;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio JPA para la entidad raíz {@link InventoryCount}.
 */
public interface InventoryCountRepository extends JpaRepository<InventoryCount, Long> {

    /**
     * Busca una toma por identificador y local activo para aislar los datos por tienda.
     *
     * @param id identificador de la toma
     * @param storeId identificador del local activo
     * @return toma si existe y pertenece al local indicado
     */
    Optional<InventoryCount> findByIdAndStoreId(Long id, Long storeId);
}
