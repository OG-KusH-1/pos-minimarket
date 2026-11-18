package cl.minimarket.pos.organization;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * API pública de locales del módulo {@code organization} (PLAN A.4).
 */
public interface StoreApi {

    /**
     * Obtiene un local por su id o lanza 404 {@code NOT_FOUND} si no existe.
     */
    StoreInfo get(Long id);

    /**
     * Busca un local por su id.
     */
    Optional<StoreInfo> find(Long id);

    /**
     * Devuelve los ids de todos los locales activos (ordenados por id ascendente).
     */
    List<Long> activeStoreIds();

    /**
     * Devuelve la información de los locales activos cuyos ids están en la colección dada,
     * ordenados por id ascendente.
     */
    List<StoreInfo> findActiveByIds(Collection<Long> ids);
}
