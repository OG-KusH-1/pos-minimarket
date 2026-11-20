package cl.minimarket.pos.promotions.web;

import cl.minimarket.pos.promotions.PromotionTargetType;
import cl.minimarket.pos.promotions.PromotionType;
import cl.minimarket.pos.promotions.application.PromotionService.PromotionCommand;
import cl.minimarket.pos.promotions.application.PromotionService.PromotionItemCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Solicitud de creación o actualización de una promoción ({@code API.md} F.3).
 */
@Schema(name = "PromotionRequest", description = "Datos para crear o actualizar una promoción.")
public record PromotionRequest(
        @Schema(description = "Nombre visible de la promoción (3 a 120 caracteres).")
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(min = 3, max = 120, message = "El nombre debe tener entre 3 y 120 caracteres.")
        String name,

        @Schema(description = "Descripción opcional de la promoción (máximo 500 caracteres).")
        @Size(max = 500, message = "La descripción no puede superar los 500 caracteres.")
        String description,

        @Schema(description = "Tipo de promoción.")
        @NotNull(message = "El tipo de promoción es obligatorio.")
        PromotionType promotionType,

        @Schema(description = "Prioridad ascendente de evaluación (1 a 1000; por defecto 100).")
        @Min(value = 1, message = "La prioridad mínima es 1.")
        @Max(value = 1000, message = "La prioridad máxima es 1000.")
        Integer priority,

        @Schema(description = "Indica si es acumulable con otras promociones.")
        Boolean stackable,

        @Schema(description = "Inicio de vigencia UTC (inclusive).")
        @NotNull(message = "La fecha de inicio es obligatoria.")
        Instant validFrom,

        @Schema(description = "Término de vigencia UTC (exclusivo, opcional).")
        Instant validTo,

        @Schema(description = "Días de la semana ISO-8601 (1=lunes..7=domingo); vacío/nulo = todos.")
        List<Integer> daysOfWeek,

        @Schema(description = "Hora inicial en zona de negocio (opcional junto a timeTo).")
        LocalTime timeFrom,

        @Schema(description = "Hora final en zona de negocio (opcional junto a timeFrom).")
        LocalTime timeTo,

        @Schema(description = "Indica si aplica a todos los locales (por defecto true).")
        Boolean allStores,

        @Schema(description = "Locales habilitados cuando allStores es false.")
        List<Long> storeIds,

        @Schema(description = "Porcentaje de descuento en (0, 100] para PERCENT.")
        BigDecimal percent,

        @Schema(description = "Monto fijo de descuento o precio unitario para FIXED_AMOUNT / QUANTITY_PRICE.")
        BigDecimal amount,

        @Schema(description = "Cantidad que se lleva en BUY_X_PAY_Y.")
        Integer buyQty,

        @Schema(description = "Cantidad que se paga en BUY_X_PAY_Y.")
        Integer payQty,

        @Schema(description = "Cantidad mínima desde la cual aplica QUANTITY_PRICE.")
        BigDecimal minQty,

        @Schema(description = "Precio cerrado del combo en CLP para COMBO.")
        BigDecimal comboPrice,

        @Schema(description = "Máximo de aplicaciones por venta (opcional, >= 1).")
        @Min(value = 1, message = "El máximo de aplicaciones por venta debe ser al menos 1.")
        Integer maxApplicationsPerSale,

        @Schema(description = "Ítems objetivo de la promoción.")
        @NotEmpty(message = "Debe incluir al menos un ítem objetivo.")
        @Valid
        List<PromotionItemRequest> items,

        @Schema(description = "Versión optimista (obligatoria en PUT).")
        Long version
) {

    /**
     * Convierte la solicitud HTTP al comando de aplicación.
     */
    public PromotionCommand toCommand() {
        List<PromotionItemCommand> itemCommands = items == null
                ? List.of()
                : items.stream()
                        .map(i -> new PromotionItemCommand(i.targetType(), i.targetId(), i.requiredQty()))
                        .toList();
        return new PromotionCommand(
                name,
                description,
                promotionType,
                priority,
                stackable,
                validFrom,
                validTo,
                daysOfWeek,
                timeFrom,
                timeTo,
                allStores,
                storeIds,
                percent,
                amount,
                buyQty,
                payQty,
                minQty,
                comboPrice,
                maxApplicationsPerSale,
                itemCommands,
                version
        );
    }

    /**
     * Ítem objetivo de una promoción en {@link PromotionRequest}.
     */
    @Schema(name = "PromotionItemRequest", description = "Regla de alcance por producto, categoría o marca.")
    public record PromotionItemRequest(
            @Schema(description = "Tipo de objetivo (PRODUCT, CATEGORY, BRAND).")
            @NotNull(message = "El tipo de objetivo es obligatorio.")
            PromotionTargetType targetType,

            @Schema(description = "Identificador del producto, categoría o marca.")
            @NotNull(message = "El identificador del objetivo es obligatorio.")
            Long targetId,

            @Schema(description = "Cantidad requerida (por defecto 1).")
            BigDecimal requiredQty
    ) {
    }
}
