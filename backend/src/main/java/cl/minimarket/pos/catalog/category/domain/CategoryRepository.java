package cl.minimarket.pos.catalog.category.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repositorio JPA de categorías de producto ({@code categories}).
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Lista todas las categorías ordenadas alfabéticamente por nombre. */
    List<Category> findAllByOrderByNameAsc();

    /** Indica si existen subcategorías cuyo padre sea la categoría indicada. */
    boolean existsByParentId(Long parentId);

    /** Verifica si existe otra categoría raíz con el mismo nombre (insensible a mayúsculas). */
    @Query("""
            SELECT COUNT(c) > 0
            FROM Category c
            WHERE c.parentId IS NULL
              AND lower(c.name) = lower(:name)
              AND (:excludeId IS NULL OR c.id <> :excludeId)
            """)
    boolean existsRootByNameIgnoreCase(@Param("name") String name, @Param("excludeId") Long excludeId);

    /** Verifica si existe otra subcategoría con el mismo nombre bajo el mismo padre. */
    @Query("""
            SELECT COUNT(c) > 0
            FROM Category c
            WHERE c.parentId = :parentId
              AND lower(c.name) = lower(:name)
              AND (:excludeId IS NULL OR c.id <> :excludeId)
            """)
    boolean existsChildByNameIgnoreCase(@Param("parentId") Long parentId,
                                        @Param("name") String name,
                                        @Param("excludeId") Long excludeId);
}
