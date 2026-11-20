package cl.minimarket.pos.promotions.web.pos;

import cl.minimarket.pos.promotions.PromotionType;
import cl.minimarket.pos.promotions.application.PromotionService.ActivePromotionView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resumen de promoción vigente para el POS ({@code API.md} F.3).
 */
@Schema(name = "ActivePromotionResponse", description = "Promoción vigente en el local y hora actuales.")
public record ActivePromotionResponse(
        @Schema(description = "Identificador único de la promoción.")
        Long id,

        @Schema(description = "Nombre visible de la promoción.")
        String name,

        @Schema(description = "Tipo de promoción.")
        PromotionType promotionType,

        @Schema(description = "Etiqueta resumida del beneficio (p. ej. 20% DCTO, 3x2).")
        String label
) {
    public static ActivePromotionResponse from(ActivePromotionView view) {
        return new ActivePromotionResponse(view.id(), view.name(), view.promotionType(), view.label());
    }
}
