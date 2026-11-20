package cl.minimarket.pos.replenishment.application.params;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositorio JPA de parámetros de reabastecimiento ({@code replenishment_params}).
 *
 * <p>La tabla es pequeña (una fila por alcance, la UQ usa {@code COALESCE(...,0)}) y JPA no expresa
 * ese índice cómodamente: la búsqueda por alcance exacto se hace con {@link #findByScope} y el
 * emparejamiento de claves nulas (local/categoría/producto) lo hace el servicio en memoria.
 */
public interface ReplenishmentParamRepository extends JpaRepository<ReplenishmentParam, Long> {

    /** Todas las filas de un alcance (para listar por alcance y para el upsert). */
    List<ReplenishmentParam> findByScope(ReplenishmentParamScope scope);
}
