package cl.minimarket.pos.catalog.scale.web;

import cl.minimarket.pos.catalog.scale.domain.EmbeddedType;
import cl.minimarket.pos.catalog.scale.domain.ScaleBarcodeConfig;

/**
 * Respuesta pública de configuración de código de barras de balanza ({@code API.md} F.3).
 */
public record ScaleBarcodeConfigResponse(
        Long id,
        String name,
        String prefix,
        EmbeddedType embeddedType,
        short pluStart,
        short pluLength,
        short valueStart,
        short valueLength,
        short valueDecimals,
        boolean active
) {

    /** Convierte una entidad {@link ScaleBarcodeConfig} a su DTO de respuesta. */
    public static ScaleBarcodeConfigResponse from(ScaleBarcodeConfig config) {
        return new ScaleBarcodeConfigResponse(
                config.getId(),
                config.getName(),
                config.getPrefix(),
                config.getEmbeddedType(),
                config.getPluStart(),
                config.getPluLength(),
                config.getValueStart(),
                config.getValueLength(),
                config.getValueDecimals(),
                config.isActive()
        );
    }
}
