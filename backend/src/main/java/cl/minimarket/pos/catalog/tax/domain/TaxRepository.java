package cl.minimarket.pos.catalog.tax.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositorio JPA de impuestos ({@code taxes}).
 */
public interface TaxRepository extends JpaRepository<Tax, Long> {

    /** Lista todos los impuestos ordenados por id ascendente. */
    List<Tax> findAllByOrderByIdAsc();

    /** Indica si ya existe un impuesto con el código dado. */
    boolean existsByCode(String code);

    /** Indica si existe otro impuesto distinto al {@code id} dado con el mismo código. */
    boolean existsByCodeAndIdNot(String code, Long id);
}
