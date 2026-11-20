package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.replenishment.application.params.ReplenishmentParam;
import cl.minimarket.pos.replenishment.application.params.ReplenishmentParamScope;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Representación HTTP de una fila de parámetros de reabastecimiento ({@code API.md} F.3
 * {@code ReplenishmentParamsResponse}): los campos de la solicitud más el identificador.
 *
 * @param id               identificador de la fila
 * @param scope            alcance (GLOBAL, STORE, CATEGORY o PRODUCT)
 * @param storeId          local (según el alcance)
 * @param categoryId       categoría (solo CATEGORY)
 * @param productId        producto (solo PRODUCT)
 * @param windowDays       ventana W, o {@code null} si hereda del nivel superior
 * @param minHistoryDays   mínimo de días válidos, o {@code null} si hereda
 * @param serviceLevelZ    factor z, o {@code null} si hereda
 * @param safetyMinDays    días mínimos de seguridad Smin, o {@code null} si hereda
 * @param reviewPeriodDays período R explícito, o {@code null} para el automático
 * @param outlierK         multiplicador k del recorte, o {@code null} si hereda
 * @param shelfLifeFactor  fracción f de vida útil, o {@code null} si hereda
 * @param enabled          participación en corridas, o {@code null} si hereda
 */
@Schema(name = "ReplenishmentParamsResponse", description = "Parámetros de reabastecimiento por alcance.")
public record ReplenishmentParamsResponse(
        @Schema(description = "Identificador único de la fila.") Long id,
        @Schema(description = "Alcance de la fila.") ReplenishmentParamScope scope,
        @Schema(description = "Local (STORE, o CATEGORY/PRODUCT acotado al local).") Long storeId,
        @Schema(description = "Categoría (solo CATEGORY).") Long categoryId,
        @Schema(description = "Producto (solo PRODUCT).") Long productId,
        @Schema(description = "Ventana W en días (null hereda del nivel superior).") Integer windowDays,
        @Schema(description = "Mínimo de días válidos para estimar demanda (null hereda).") Integer minHistoryDays,
        @Schema(description = "Factor z del nivel de servicio (null hereda).") BigDecimal serviceLevelZ,
        @Schema(description = "Días mínimos de stock de seguridad (null hereda).") BigDecimal safetyMinDays,
        @Schema(description = "Período R explícito (null usa el automático de C.2).") Integer reviewPeriodDays,
        @Schema(description = "Multiplicador k del recorte mediana/MAD (null hereda).") BigDecimal outlierK,
        @Schema(description = "Fracción de vida útil utilizable (null hereda).") BigDecimal shelfLifeFactor,
        @Schema(description = "Participación en las corridas (null hereda).") Boolean enabled
) {

    /** Proyecta desde la entidad. */
    public static ReplenishmentParamsResponse from(ReplenishmentParam row) {
        return new ReplenishmentParamsResponse(
                row.getId(),
                row.getScope(),
                row.getStoreId(),
                row.getCategoryId(),
                row.getProductId(),
                row.getWindowDays(),
                row.getMinHistoryDays(),
                row.getServiceLevelZ(),
                row.getSafetyMinDays(),
                row.getReviewPeriodDays(),
                row.getOutlierK(),
                row.getShelfLifeFactor(),
                row.getEnabled());
    }
}
