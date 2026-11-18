package cl.minimarket.pos.organization.web;

import cl.minimarket.pos.organization.SettingScope;
import cl.minimarket.pos.organization.SettingValueType;
import cl.minimarket.pos.organization.application.SettingsService;
import tools.jackson.databind.JsonNode;

/**
 * DTO de respuesta de un parámetro de configuración ({@code API.md} F.3 {@code SettingResponse}).
 */
public record SettingResponse(
        String key,
        SettingScope scope,
        Long storeId,
        JsonNode value,
        SettingValueType valueType,
        String description
) {

    /** Construye el DTO desde la vista de servicio. */
    public static SettingResponse from(SettingsService.SettingView view) {
        return new SettingResponse(
                view.key(),
                view.scope(),
                view.storeId(),
                view.value(),
                view.valueType(),
                view.description()
        );
    }
}
