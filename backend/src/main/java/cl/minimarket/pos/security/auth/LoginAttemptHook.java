package cl.minimarket.pos.security.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Punto de extensión entre T19 (autenticación núcleo) y T21 (refresh tokens, rate limiting y
 * bloqueo de cuenta por intentos fallidos).
 *
 * <p>T19 provee una implementación por defecto sin efectos secundarios. Cuando T21 implemente un
 * bean de esta interfaz, podrá interceptar los eventos del ciclo de login y cambio de contraseña sin
 * modificar {@link AuthService} ni {@link AuthController}.
 */
public interface LoginAttemptHook {

    /**
     * Se invoca antes de procesar el login (punto para rate limiting por IP y usuario objetivo, ADR-21).
     */
    default void beforePasswordLogin(String username, HttpServletRequest request) {
    }

    /**
     * Se invoca tras un intento fallido de login con contraseña (punto para incrementar
     * {@code failed_attempts}, activar {@code locked_until} y registrar en {@code login_attempts}).
     */
    default void onPasswordLoginFailure(String username, AuthUserEntity user, String reason, HttpServletRequest request) {
    }

    /**
     * Se invoca tras un login exitoso con contraseña (punto para reiniciar {@code failed_attempts},
     * registrar en {@code login_attempts} y emitir la cookie {@code pos_refresh}).
     */
    default void onPasswordLoginSuccess(AuthUserEntity user, HttpServletRequest request, HttpServletResponse response) {
    }

    /**
     * Se invoca tras cambiar la contraseña del usuario actual (punto para revocar otras sesiones
     * activas en {@code refresh_tokens}, API.md F.2).
     */
    default void onPasswordChanged(Long userId) {
    }
}
