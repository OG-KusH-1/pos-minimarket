package cl.minimarket.pos.security.pin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Solicitud para configurar o cambiar el PIN personal del usuario autenticado
 * ({@code PUT /api/v1/auth/me/pin}, API.md F.3).
 *
 * @param currentPassword contraseña actual del usuario para confirmar identidad
 * @param newPin nuevo PIN numérico de 4 a 6 dígitos (sin dígitos todos iguales ni secuencia)
 */
public record ChangePinRequest(
        @NotBlank(message = "La contraseña actual es obligatoria")
        String currentPassword,

        @NotBlank(message = "El nuevo PIN es obligatorio")
        @Pattern(regexp = "^\\d{4,6}$", message = "El PIN debe tener entre 4 y 6 dígitos")
        String newPin
) {
}
