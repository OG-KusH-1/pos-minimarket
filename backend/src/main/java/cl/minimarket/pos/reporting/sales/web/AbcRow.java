package cl.minimarket.pos.reporting.sales.web;

import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.AbcView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila del análisis Pareto ABC ({@code GET /api/v1/reports/abc}, {@code API.md} F.3): clase
 * {@code A} hasta 80 % acumulado, {@code B} hasta 95 % y {@code C} el resto.
 */
@Schema(name = "AbcRow", description = "Clasificación ABC de un producto vendido en el rango.")
public record AbcRow(
        @Schema(description = "Identificador del producto.")
        Long productId,
        @Schema(description = "SKU del producto.")
        String sku,
        @Schema(description = "Nombre del producto.")
        String name,
        @Schema(description = "Valor de la métrica evaluada (venta neta, cantidad o margen bruto).")
        BigDecimal metricValue,
        @Schema(description = "Participación porcentual individual (escala 4).")
        BigDecimal pct,
        @Schema(description = "Porcentaje acumulado descendente (escala 4).")
        BigDecimal cumulativePct,
        @Schema(description = "Clase Pareto asignada (A, B o C).")
        String abcClass
) {

    /** Convierte la proyección de aplicación al DTO HTTP. */
    public static AbcRow from(AbcView view) {
        return new AbcRow(
                view.productId(),
                view.sku(),
                view.name(),
                view.metricValue(),
                view.pct(),
                view.cumulativePct(),
                view.abcClass());
    }
}
