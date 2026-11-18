package cl.minimarket.pos.catalog.product.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio JPA de definiciones de pack ({@code product_packs}).
 */
public interface ProductPackRepository extends JpaRepository<ProductPack, Long> {

    /** Busca la definición de pack para el producto dado. */
    Optional<ProductPack> findByProductId(Long productId);
}
