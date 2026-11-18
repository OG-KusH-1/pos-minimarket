package cl.minimarket.pos.organization.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio de la empresa singleton ({@code company}).
 */
public interface CompanyRepository extends JpaRepository<Company, Long> {

    /** Devuelve la única fila de {@code company}, si existe. */
    Optional<Company> findTopByOrderByIdAsc();
}
