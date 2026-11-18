package cl.minimarket.pos.security.auth.refresh;

import jakarta.validation.constraints.NotNull;

/**
 * Solicitud para asociar la sesión vigente (refresh token) a una terminal POS
 * ({@code POST /api/v1/auth/session/terminal}, API.md F.3).
 *
 * @param terminalId identificador de la terminal activa dentro del local de {@code X-Store-Id}
 */
public record SessionTerminalRequest(
        @NotNull(message = "El identificador de la terminal es obligatorio.")
        Long terminalId
) {
}
