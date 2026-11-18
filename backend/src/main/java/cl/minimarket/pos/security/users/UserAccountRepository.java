package cl.minimarket.pos.security.users;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio JPA de cuentas de usuario ({@code users}).
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** Verifica si existe otro usuario con el mismo username (case-insensitive). */
    boolean existsByUsernameIgnoreCase(String username);

    /** Verifica si existe otro usuario distinto de {@code id} con el mismo username. */
    boolean existsByUsernameIgnoreCaseAndIdNot(String username, Long id);

    /** Verifica si existe un usuario con el email indicado (case-insensitive). */
    boolean existsByEmailIgnoreCase(String email);

    /** Verifica si existe otro usuario distinto de {@code id} con el email indicado. */
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    /** Verifica si existe un usuario con el RUT normalizado indicado. */
    boolean existsByRut(String rut);

    /** Verifica si existe otro usuario distinto de {@code id} con el RUT normalizado indicado. */
    boolean existsByRutAndIdNot(String rut, Long id);
}
