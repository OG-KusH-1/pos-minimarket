package cl.minimarket.pos.organization.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio de terminales POS ({@code terminals}).
 */
public interface TerminalRepository extends JpaRepository<Terminal, Long> {

    /** Lista las terminales de un local ordenadas por código. */
    List<Terminal> findByStoreIdOrderByCodeAsc(Long storeId);

    /** Busca una terminal verificando que pertenezca al local. */
    Optional<Terminal> findByIdAndStoreId(Long id, Long storeId);

    /** Verifica si existe una terminal con el código dado dentro del local. */
    boolean existsByStoreIdAndCode(Long storeId, String code);

    /** Verifica si existe otra terminal con el código dado dentro del local. */
    boolean existsByStoreIdAndCodeAndIdNot(Long storeId, String code, Long id);
}
