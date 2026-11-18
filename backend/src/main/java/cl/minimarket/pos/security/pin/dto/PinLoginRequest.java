package cl.minimarket.pos.security.pin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Solicitud de cambio rápido de operador por PIN en una terminal POS
 * ({@code POST /api/v1/auth/pin-login}, API.md F.3).
 *
 * @param username nombre de usuario del operador que inicia turno en la terminal
 * @param pin PIN numérico de 4 a 6 dígitos
 */
public record PinLoginRequest(
        @NotBlank(message = "El nombre de usuario es obligatorio")
        String username,

        @NotBlank(message = "El PIN es obligatorio")
        @Pattern(regexp = "^\\d{4,6}$", message = "El PIN debe tener entre 4 y 6 dígitos")
        String pin
) {
}
