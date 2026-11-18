package cl.minimarket.pos.organization.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Repositorio de locales ({@code stores}).
 */
public interface StoreRepository extends JpaRepository<Store, Long> {

    /** Verifica si existe un local con el código dado. */
    boolean existsByCode(String code);

    /** Verifica si existe otro local distinto con el código dado. */
    boolean existsByCodeAndIdNot(String code, Long id);

    /** Devuelve los ids de todos los locales activos ordenados por id. */
    @Query("SELECT s.id FROM Store s WHERE s.active = true ORDER BY s.id ASC")
    List<Long> findActiveIds();

    /** Lista locales filtrando opcionalmente por estado activo y por un conjunto de ids permitidos. */
    Page<Store> findByActive(boolean active, Pageable pageable);

    /** Lista locales cuyos ids están en la colección dada. */
    Page<Store> findByIdIn(Collection<Long> ids, Pageable pageable);

    /** Lista locales cuyos ids están en la colección dada y con estado activo dado. */
    Page<Store> findByIdInAndActive(Collection<Long> ids, boolean active, Pageable pageable);

    /** Devuelve los ids de locales asignados al usuario en {@code user_stores}. */
    @Query(value = "SELECT store_id FROM user_stores WHERE user_id = :userId", nativeQuery = true)
    List<Long> findAssignedStoreIdsByUserId(@Param("userId") Long userId);
}
