package cl.minimarket.pos.customers.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repositorio JPA de clientes ({@code customers}).
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Obtiene un cliente bloqueando la fila con {@code FOR UPDATE} para serializar cambios de deuda
     * o cupo en cuenta corriente.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    boolean existsByRut(String rut);

    boolean existsByRutAndIdNot(String rut, Long id);
}
