package cl.minimarket.pos.security.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio JPA de usuarios para autenticación núcleo.
 */
public interface AuthUserRepository extends JpaRepository<AuthUserEntity, Long> {

    /**
     * Busca un usuario por nombre de usuario sin distinguir mayúsculas/minúsculas
     * (índice único {@code uq_users_username_lower}).
     */
    Optional<AuthUserEntity> findByUsernameIgnoreCase(String username);
}
