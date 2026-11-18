package cl.minimarket.pos.organization.web;

import cl.minimarket.pos.organization.SettingScope;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * DTO para actualizar un parámetro de configuración ({@code API.md} F.3 {@code SettingUpdateRequest}).
 */
public record SettingUpdateRequest(
        @NotNull SettingScope scope,
        @NotNull JsonNode value
) {
}
