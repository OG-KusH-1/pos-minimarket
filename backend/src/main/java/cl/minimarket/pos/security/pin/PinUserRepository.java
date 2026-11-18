package cl.minimarket.pos.security.pin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Repositorio JDBC sobre la tabla {@code users} para los flujos de PIN y overrides de supervisor.
 *
 * <p>Permite leer el estado completo de credenciales (contraseña, PIN y sus respectivos contadores
 * y bloqueos) y actualizarlo atómicamente sin depender de la entidad JPA de autenticación núcleo.
 */
@Repository
public class PinUserRepository {

    private final JdbcClient jdbc;

    /**
     * @param jdbc cliente JDBC de Spring
     */
    public PinUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Busca un usuario por identificador.
     */
    public Optional<UserCredentialView> findById(Long userId) {
        return jdbc.sql(SELECT_BASE + " WHERE id = :id")
                .param("id", userId)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * Busca un usuario por nombre de usuario ignorando mayúsculas/minúsculas.
     */
    public Optional<UserCredentialView> findByUsernameIgnoreCase(String username) {
        return jdbc.sql(SELECT_BASE + " WHERE lower(username) = lower(:username)")
                .param("username", username)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * Actualiza el hash del PIN del usuario y reinicia los contadores de bloqueo del PIN.
     */
    public void updatePinHashAndResetFailures(Long userId, String pinHash, Instant updatedAt) {
        jdbc.sql("""
                        UPDATE users
                        SET pin_hash = :pinHash,
                            pin_failed_attempts = 0,
                            pin_locked = false,
                            pin_locked_until = NULL,
                            updated_at = :updatedAt
                        WHERE id = :userId
                        """)
                .param("pinHash", pinHash)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("userId", userId)
                .update();
    }

    /**
     * Actualiza el estado de intentos fallidos / bloqueo del PIN.
     */
    public void updatePinFailureState(
            Long userId,
            int failedAttempts,
            boolean locked,
            Instant lockedUntil,
            Instant updatedAt
    ) {
        jdbc.sql("""
                        UPDATE users
                        SET pin_failed_attempts = :failedAttempts,
                            pin_locked = :locked,
                            pin_locked_until = :lockedUntil,
                            updated_at = :updatedAt
                        WHERE id = :userId
                        """)
                .param("failedAttempts", failedAttempts)
                .param("locked", locked)
                .param("lockedUntil", lockedUntil != null ? Timestamp.from(lockedUntil) : null)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("userId", userId)
                .update();
    }

    /**
     * Reinicia el contador y estado de bloqueo del PIN tras un intento exitoso o auto-desbloqueo.
     */
    public void resetPinFailures(Long userId, Instant updatedAt) {
        updatePinFailureState(userId, 0, false, null, updatedAt);
    }

    /**
     * Actualiza el estado de intentos fallidos y bloqueo de contraseña ({@code failed_attempts},
     * {@code locked_until}).
     */
    public void updatePasswordFailureState(
            Long userId,
            int failedAttempts,
            Instant lockedUntil,
            Instant updatedAt
    ) {
        jdbc.sql("""
                        UPDATE users
                        SET failed_attempts = :failedAttempts,
                            locked_until = :lockedUntil,
                            updated_at = :updatedAt
                        WHERE id = :userId
                        """)
                .param("failedAttempts", failedAttempts)
                .param("lockedUntil", lockedUntil != null ? Timestamp.from(lockedUntil) : null)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("userId", userId)
                .update();
    }

    /**
     * Registra la fecha y hora del último inicio de sesión exitoso.
     */
    public void updateLastLoginAt(Long userId, Instant lastLoginAt) {
        jdbc.sql("""
                        UPDATE users
                        SET last_login_at = :lastLoginAt,
                            updated_at = :lastLoginAt
                        WHERE id = :userId
                        """)
                .param("lastLoginAt", Timestamp.from(lastLoginAt))
                .param("userId", userId)
                .update();
    }

    private static final String SELECT_BASE = """
            SELECT id, username, full_name, password_hash, pin_hash,
                   pin_failed_attempts, pin_locked, pin_locked_until,
                   failed_attempts, locked_until, must_change_password, active
            FROM users
            """;

    private static final org.springframework.jdbc.core.RowMapper<UserCredentialView> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp pinLockedUntilTs = rs.getTimestamp("pin_locked_until");
        Timestamp lockedUntilTs = rs.getTimestamp("locked_until");
        return new UserCredentialView(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                rs.getString("pin_hash"),
                rs.getInt("pin_failed_attempts"),
                rs.getBoolean("pin_locked"),
                pinLockedUntilTs != null ? pinLockedUntilTs.toInstant() : null,
                rs.getInt("failed_attempts"),
                lockedUntilTs != null ? lockedUntilTs.toInstant() : null,
                rs.getBoolean("must_change_password"),
                rs.getBoolean("active")
        );
    };

    /**
     * Proyección de credenciales y estado de bloqueo de un usuario.
     */
    public record UserCredentialView(
            Long id,
            String username,
            String fullName,
            String passwordHash,
            String pinHash,
            int pinFailedAttempts,
            boolean pinLocked,
            Instant pinLockedUntil,
            int failedAttempts,
            Instant lockedUntil,
            boolean mustChangePassword,
            boolean active
    ) {
    }
}
