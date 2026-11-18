package cl.minimarket.pos.organization.web;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * DTO de parámetros efectivos para el local activo ({@code API.md} F.3
 * {@code EffectiveSettingsResponse}).
 */
public record EffectiveSettingsResponse(
        Map<String, JsonNode> values
) {
}
