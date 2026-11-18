package cl.minimarket.pos.security.roles;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio JPA para la gestión de roles ({@code roles}).
 */
public interface RoleRepository extends JpaRepository<RoleEntity, Long> {

    /**
     * Busca un rol por su código único (p. ej. {@code ADMIN}, {@code MANAGER}).
     */
    Optional<RoleEntity> findByCode(String code);

    /**
     * Indica si existe un rol con el código indicado.
     */
    boolean existsByCode(String code);
}
