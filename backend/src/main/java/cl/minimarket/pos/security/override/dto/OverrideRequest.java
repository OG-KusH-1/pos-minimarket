package cl.minimarket.pos.security.override.dto;

import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.override.OverrideMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Solicitud de autorización de supervisor ({@code POST /api/v1/auth/overrides}, API.md F.3).
 *
 * @param action acción de negocio a autorizar
 * @param context objeto JSON con el contexto de la operación (el servidor calcula su {@code context_hash} canónico)
 * @param supervisorUsername nombre de usuario del supervisor que autoriza
 * @param method método de verificación ({@code PIN} o {@code PASSWORD})
 * @param secret PIN o contraseña del supervisor
 * @param reason motivo opcional de la autorización (0..250 caracteres)
 */
public record OverrideRequest(
        @NotNull(message = "La acción es obligatoria")
        OverrideAction action,

        @NotNull(message = "El contexto es obligatorio")
        JsonNode context,

        @NotBlank(message = "El usuario del supervisor es obligatorio")
        @Size(max = 50, message = "El usuario del supervisor no puede superar los 50 caracteres")
        String supervisorUsername,

        @NotNull(message = "El método de autenticación es obligatorio")
        OverrideMethod method,

        @NotBlank(message = "La credencial del supervisor es obligatoria")
        @Size(min = 1, max = 128, message = "La credencial debe tener entre 1 y 128 caracteres")
        String secret,

        @Size(max = 250, message = "El motivo no puede superar los 250 caracteres")
        String reason
) {
}
