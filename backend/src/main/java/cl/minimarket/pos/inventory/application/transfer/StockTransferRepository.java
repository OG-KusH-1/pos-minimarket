package cl.minimarket.pos.inventory.application.transfer;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Repositorio JPA para la raíz de agregado {@link StockTransfer}. */
public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    /**
     * Carga una transferencia junto con sus líneas ordenadas por identificador.
     *
     * @param id identificador de la transferencia.
     * @return transferencia con sus líneas inicializadas si existe.
     */
    @EntityGraph(attributePaths = "lines")
    Optional<StockTransfer> findWithLinesById(Long id);
}
