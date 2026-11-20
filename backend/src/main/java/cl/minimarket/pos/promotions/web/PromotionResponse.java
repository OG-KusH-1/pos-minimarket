package cl.minimarket.pos.promotions.web;

import cl.minimarket.pos.promotions.PromotionTargetType;
import cl.minimarket.pos.promotions.PromotionType;
import cl.minimarket.pos.promotions.application.PromotionService.PromotionItemView;
import cl.minimarket.pos.promotions.application.PromotionService.PromotionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Representación HTTP completa de una promoción ({@code API.md} F.3).
 */
@Schema(name = "PromotionResponse", description = "Detalle completo de una promoción.")
public record PromotionResponse(
        @Schema(description = "Identificador único de la promoción.")
        Long id,

        @Schema(description = "Nombre visible.")
        String name,

        @Schema(description = "Descripción opcional.")
        String description,

        @Schema(description = "Tipo de promoción.")
        PromotionType promotionType,

        @Schema(description = "Prioridad ascendente de evaluación.")
        int priority,

        @Schema(description = "Indica si es acumulable.")
        boolean stackable,

        @Schema(description = "Inicio de vigencia UTC.")
        Instant validFrom,

        @Schema(description = "Término de vigencia UTC (opcional).")
        Instant validTo,

        @Schema(description = "Días de la semana ISO-8601 (1=lunes..7=domingo).")
        List<Integer> daysOfWeek,

        @Schema(description = "Hora de inicio en zona de negocio.")
        LocalTime timeFrom,

        @Schema(description = "Hora de término en zona de negocio.")
        LocalTime timeTo,

        @Schema(description = "Indica si rige en todos los locales.")
        boolean allStores,

        @Schema(description = "Locales habilitados cuando allStores es false.")
        List<Long> storeIds,

        @Schema(description = "Porcentaje de descuento para PERCENT.")
        BigDecimal percent,

        @Schema(description = "Monto de descuento o precio unitario para FIXED_AMOUNT / QUANTITY_PRICE.")
        BigDecimal amount,

        @Schema(description = "Cantidad que se lleva en BUY_X_PAY_Y.")
        Integer buyQty,

        @Schema(description = "Cantidad que se paga en BUY_X_PAY_Y.")
        Integer payQty,

        @Schema(description = "Cantidad mínima en QUANTITY_PRICE.")
        BigDecimal minQty,

        @Schema(description = "Precio cerrado en COMBO.")
        BigDecimal comboPrice,

        @Schema(description = "Máximo de aplicaciones por venta.")
        Integer maxApplicationsPerSale,

        @Schema(description = "Indica si la promoción está activa.")
        boolean active,

        @Schema(description = "Ítems objetivo de la promoción.")
        List<PromotionItemResponse> items,

        @Schema(description = "Versión para bloqueo optimista.")
        Long version
) {

    /**
     * Convierte una vista de aplicación a DTO HTTP.
     */
    public static PromotionResponse from(PromotionView view) {
        List<PromotionItemResponse> itemResponses = view.items().stream()
                .map(PromotionItemResponse::from)
                .toList();
        return new PromotionResponse(
                view.id(),
                view.name(),
                view.description(),
                view.promotionType(),
                view.priority(),
                view.stackable(),
                view.validFrom(),
                view.validTo(),
                view.daysOfWeek(),
                view.timeFrom(),
                view.timeTo(),
                view.allStores(),
                view.storeIds(),
                view.percent(),
                view.amount(),
                view.buyQty(),
                view.payQty(),
                view.minQty(),
                view.comboPrice(),
                view.maxApplicationsPerSale(),
                view.active(),
                itemResponses,
                view.version()
        );
    }

    /**
     * Ítem objetivo de una promoción con su nombre resuelto.
     */
    @Schema(name = "PromotionItemResponse", description = "Ítem objetivo de una promoción.")
    public record PromotionItemResponse(
            @Schema(description = "Identificador del ítem.")
            Long id,

            @Schema(description = "Tipo de objetivo (PRODUCT, CATEGORY, BRAND).")
            PromotionTargetType targetType,

            @Schema(description = "Identificador del producto, categoría o marca.")
            Long targetId,

            @Schema(description = "Nombre del producto, categoría o marca.")
            String targetName,

            @Schema(description = "Cantidad requerida.")
            BigDecimal requiredQty
    ) {
        public static PromotionItemResponse from(PromotionItemView view) {
            return new PromotionItemResponse(
                    view.id(),
                    view.targetType(),
                    view.targetId(),
                    view.targetName(),
                    view.requiredQty()
            );
        }
    }
}
