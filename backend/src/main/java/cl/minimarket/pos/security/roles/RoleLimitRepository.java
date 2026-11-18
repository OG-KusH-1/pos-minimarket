package cl.minimarket.pos.security.roles;

import cl.minimarket.pos.security.RoleLimitCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA para la tabla {@code role_limits}.
 */
public interface RoleLimitRepository extends JpaRepository<RoleLimitEntity, Long> {

    /**
     * Obtiene todas las filas de límite configuradas para un rol, ordenadas por código.
     */
    List<RoleLimitEntity> findByRoleIdOrderByLimitCodeAsc(Long roleId);

    /**
     * Busca la fila de un código específico para un rol dado.
     */
    Optional<RoleLimitEntity> findByRoleIdAndLimitCode(Long roleId, RoleLimitCode limitCode);
}
