package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.replenishment.application.params.ReplenishmentParamScope;
import cl.minimarket.pos.replenishment.application.params.ReplenishmentParamsService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Solicitud de upsert de parámetros de reabastecimiento por alcance ({@code API.md} F.3
 * {@code ReplenishmentParamsRequest}).
 *
 * <p>Un valor {@code null} significa "heredar del nivel superior". La coherencia del alcance con
 * sus claves y la existencia de las referencias la valida el servicio.
 */
@Schema(name = "ReplenishmentParamsRequest", description = "Upsert de parámetros por alcance (null hereda).")
public record ReplenishmentParamsRequest(
        @Schema(description = "Alcance de la fila (obligatorio).")
        @NotNull
        ReplenishmentParamScope scope,

        @Schema(description = "Local (obligatorio en STORE; opcional en CATEGORY y PRODUCT).")
        Long storeId,

        @Schema(description = "Categoría (obligatoria en CATEGORY).")
        Long categoryId,

        @Schema(description = "Producto (obligatorio en PRODUCT).")
        Long productId,

        @Schema(description = "Ventana W en días.", minimum = "7", maximum = "365")
        @Min(7) @Max(365)
        Integer windowDays,

        @Schema(description = "Mínimo de días válidos para estimar demanda.", minimum = "1", maximum = "90")
        @Min(1) @Max(90)
        Integer minHistoryDays,

        @Schema(description = "Factor z del nivel de servicio.", minimum = "0", maximum = "3.5")
        @DecimalMin("0") @DecimalMax("3.5")
        BigDecimal serviceLevelZ,

        @Schema(description = "Días mínimos de stock de seguridad.", minimum = "0", maximum = "60")
        @DecimalMin("0") @DecimalMax("60")
        BigDecimal safetyMinDays,

        @Schema(description = "Período R explícito; null usa el automático de C.2.", minimum = "1", maximum = "60")
        @Min(1) @Max(60)
        Integer reviewPeriodDays,

        @Schema(description = "Multiplicador k del recorte mediana/MAD.", minimum = "1", maximum = "10")
        @DecimalMin("1") @DecimalMax("10")
        BigDecimal outlierK,

        @Schema(description = "Fracción de la vida útil utilizable para el tope.", minimum = "0.1", maximum = "1")
        @DecimalMin("0.1") @DecimalMax("1")
        BigDecimal shelfLifeFactor,

        @Schema(description = "Participación en las corridas; null hereda del nivel superior.")
        Boolean enabled
) {

    /** Convierte a comando de servicio. */
    public ReplenishmentParamsService.ReplenishmentParamCommand toCommand() {
        return new ReplenishmentParamsService.ReplenishmentParamCommand(
                scope, storeId, categoryId, productId,
                windowDays, minHistoryDays, serviceLevelZ, safetyMinDays,
                reviewPeriodDays, outlierK, shelfLifeFactor, enabled);
    }
}
