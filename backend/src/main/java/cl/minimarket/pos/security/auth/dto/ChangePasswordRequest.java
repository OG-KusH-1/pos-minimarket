package cl.minimarket.pos.security.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de cambio de contraseña del usuario autenticado ({@code POST /api/v1/auth/me/password}).
 *
 * @param currentPassword contraseña actual del usuario
 * @param newPassword nueva contraseña (el largo mínimo dinámico y la complejidad se validan en servicio)
 */
public record ChangePasswordRequest(
        @NotBlank(message = "La contraseña actual es obligatoria.")
        @Size(max = 128, message = "La contraseña actual no puede superar los 128 caracteres.")
        String currentPassword,

        @NotBlank(message = "La nueva contraseña es obligatoria.")
        @Size(max = 128, message = "La nueva contraseña no puede superar los 128 caracteres.")
        String newPassword
) {
}
