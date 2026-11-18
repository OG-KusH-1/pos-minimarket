package cl.minimarket.pos.security.users;

/**
 * Respuesta de contraseña temporal generada al crear un usuario o resetear su contraseña
 * ({@code API.md} F.3).
 *
 * @param temporaryPassword contraseña temporal de 16 caracteres (se muestra una sola vez)
 * @param mustChangePassword indica que el usuario debe cambiarla al ingresar
 */
public record TemporaryPasswordResponse(
        String temporaryPassword,
        boolean mustChangePassword
) {
}
