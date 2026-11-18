package cl.minimarket.pos.purchasing.supplier.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio JPA de proveedores ({@code suppliers}).
 */
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    /** Indica si ya existe un proveedor con el RUT normalizado dado. */
    boolean existsByRut(String rut);

    /** Indica si existe otro proveedor distinto de {@code id} con el RUT normalizado dado. */
    boolean existsByRutAndIdNot(String rut, Long id);

    /** Busca un proveedor por su RUT normalizado. */
    Optional<Supplier> findByRut(String rut);
}
