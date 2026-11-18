package cl.minimarket.pos.catalog.product.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA de productos ({@code products}).
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** Indica si ya existe un producto con el SKU dado. */
    boolean existsBySku(String sku);

    /** Indica si existe otro producto distinto al {@code id} dado con el mismo SKU. */
    boolean existsBySkuAndIdNot(String sku, Long id);

    /** Indica si ya existe un producto con el código de atajo dado. */
    boolean existsByShortcutCode(String shortcutCode);

    /** Indica si existe otro producto distinto al {@code id} dado con el mismo código de atajo. */
    boolean existsByShortcutCodeAndIdNot(String shortcutCode, Long id);

    /** Busca un producto activo por SKU exacto. */
    Optional<Product> findBySkuAndActiveTrue(String sku);

    /** Busca un producto activo por código de atajo exacto. */
    Optional<Product> findByShortcutCodeAndActiveTrue(String shortcutCode);

    /** Devuelve los identificadores de todos los productos que no son {@code PACK}. */
    @Query("""
            SELECT p.id
            FROM Product p
            WHERE p.saleType <> cl.minimarket.pos.catalog.SaleType.PACK
            ORDER BY p.id ASC
            """)
    List<Long> findNonPackProductIds();
}
