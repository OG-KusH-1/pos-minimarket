package cl.minimarket.pos.purchasing.productsupplier.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA de asociaciones producto-proveedor ({@code product_suppliers}).
 */
public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, Long> {

    /** Busca la asociación única por producto y proveedor. */
    Optional<ProductSupplier> findByProductIdAndSupplierId(Long productId, Long supplierId);

    /** Indica si ya existe una asociación para el par producto-proveedor. */
    boolean existsByProductIdAndSupplierId(Long productId, Long supplierId);

    /** Indica si existe otra asociación distinta de {@code id} para el par producto-proveedor. */
    boolean existsByProductIdAndSupplierIdAndIdNot(Long productId, Long supplierId, Long id);

    /** Devuelve las asociaciones marcadas como preferidas para un producto. */
    List<ProductSupplier> findAllByProductIdAndPreferredTrue(Long productId);
}
