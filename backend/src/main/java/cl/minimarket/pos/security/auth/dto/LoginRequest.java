package cl.minimarket.pos.security.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credenciales para iniciar sesión con usuario y contraseña ({@code POST /api/v1/auth/login}).
 *
 * @param username nombre de usuario (3 a 50 caracteres)
 * @param password contraseña en texto plano (1 a 128 caracteres)
 */
public record LoginRequest(
        @NotBlank(message = "El nombre de usuario es obligatorio.")
        @Size(min = 3, max = 50, message = "El nombre de usuario debe tener entre 3 y 50 caracteres.")
        String username,

        @NotBlank(message = "La contraseña es obligatoria.")
        @Size(min = 1, max = 128, message = "La contraseña no puede superar los 128 caracteres.")
        String password
) {
}
