package cl.minimarket.pos.catalog.product.domain;

import cl.minimarket.pos.catalog.BarcodeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA de códigos de barras de producto ({@code product_barcodes}).
 */
public interface ProductBarcodeRepository extends JpaRepository<ProductBarcode, Long> {

    /** Lista los códigos de un producto dejando primero el principal. */
    List<ProductBarcode> findByProductIdOrderByPrimaryDescIdAsc(Long productId);

    /** Busca un código de barras perteneciente a un producto específico. */
    Optional<ProductBarcode> findByIdAndProductId(Long id, Long productId);

    /** Obtiene el código de barras principal de un producto si existe. */
    Optional<ProductBarcode> findFirstByProductIdAndPrimaryTrue(Long productId);

    /** Indica si el código existe fuera del espacio {@code SCALE_PLU}. */
    boolean existsByCodeAndBarcodeTypeNot(String code, BarcodeType excludedType);

    /** Indica si el código existe dentro de un tipo específico (p. ej. {@code SCALE_PLU}). */
    boolean existsByCodeAndBarcodeType(String code, BarcodeType barcodeType);

    /** Desmarca cualquier código principal actual de un producto. */
    @Modifying
    @Query("UPDATE ProductBarcode b SET b.primary = false WHERE b.productId = :productId AND b.primary = true")
    int clearPrimaryForProduct(Long productId);
}
