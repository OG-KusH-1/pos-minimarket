package cl.minimarket.pos.security.roles;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Repositorio JPA de solo lectura para el catálogo {@code permissions}.
 */
public interface PermissionRepository extends JpaRepository<PermissionEntity, Long> {

    /**
     * Lista todos los permisos ordenados por módulo y código.
     */
    List<PermissionEntity> findAllByOrderByModuleAscCodeAsc();

    /**
     * Busca permisos cuyos códigos estén en la colección indicada.
     */
    List<PermissionEntity> findByCodeIn(Collection<String> codes);
}
