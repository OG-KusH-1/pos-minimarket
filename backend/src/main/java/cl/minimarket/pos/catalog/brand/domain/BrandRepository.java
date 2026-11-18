package cl.minimarket.pos.catalog.brand.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio JPA de marcas ({@code brands}).
 */
public interface BrandRepository extends JpaRepository<Brand, Long>, JpaSpecificationExecutor<Brand> {

    /** Verifica si existe una marca con el nombre indicado (insensible a mayúsculas). */
    @Query("""
            SELECT COUNT(b) > 0
            FROM Brand b
            WHERE lower(b.name) = lower(:name)
              AND (:excludeId IS NULL OR b.id <> :excludeId)
            """)
    boolean existsByNameIgnoreCase(@Param("name") String name, @Param("excludeId") Long excludeId);
}
