package cl.minimarket.pos.catalog.scale.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositorio JPA para configuraciones de códigos de balanza ({@code scale_barcode_configs}).
 */
public interface ScaleBarcodeConfigRepository extends JpaRepository<ScaleBarcodeConfig, Long> {

    /** Lista todas las configuraciones ordenadas por identificador ascendente. */
    List<ScaleBarcodeConfig> findAllByOrderByIdAsc();

    /** Lista las configuraciones activas para decodificación de códigos de balanza. */
    List<ScaleBarcodeConfig> findAllByActiveTrueOrderByPrefixAsc();

    /** Verifica si ya existe una configuración con el prefijo indicado. */
    boolean existsByPrefix(String prefix);

    /** Verifica si otra configuración distinta a {@code id} usa el prefijo indicado. */
    boolean existsByPrefixAndIdNot(String prefix, Long id);
}
