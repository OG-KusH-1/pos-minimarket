package cl.minimarket.pos.security.auth.ratelimit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Repositorio JDBC para registrar intentos de inicio de sesión en {@code login_attempts} (B.2).
 */
@Repository
public class LoginAttemptRepository {

    private final JdbcClient jdbc;

    /**
     * @param jdbc cliente JDBC de Spring
     */
    public LoginAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserta un registro de intento de autenticación (exitoso o fallido).
     */
    public void record(
            String username,
            Long userId,
            String method,
            String ip,
            boolean success,
            String failureReason,
            Instant attemptedAt
    ) {
        jdbc.sql("""
                        INSERT INTO login_attempts (
                            username, user_id, method, ip, success, failure_reason, attempted_at
                        )
                        VALUES (
                            :username, :userId, :method, CAST(:ip AS inet), :success, :failureReason, :attemptedAt
                        )
                        """)
                .param("username", truncate(username, 50))
                .param("userId", userId)
                .param("method", method)
                .param("ip", ip)
                .param("success", success)
                .param("failureReason", failureReason)
                .param("attemptedAt", Timestamp.from(attemptedAt))
                .update();
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
