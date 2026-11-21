package cl.minimarket.pos.reporting.inventory;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila del reporte de valorización de inventario ({@code API.md} F.3, {@code HU-REP-07}).
 *
 * <p>Valoriza el stock actual con el costo promedio ponderado vigente ({@code products.avg_cost}).
 *
 * @param key       identificador de la categoría, local o producto (o {@code TOTAL})
 * @param label     nombre legible de la agrupación
 * @param qty       cantidad total en existencia en unidad base (escala 3)
 * @param valuation valorización {@code Σ stock × avg_cost} (escala 2)
 * @param pct       participación porcentual sobre la valorización total (escala 2)
 */
@Schema(name = "InventoryValuationRow", description = "Fila del reporte de valorización de inventario.")
public record InventoryValuationRow(
        @Schema(description = "Clave de agrupación (id de categoría, local o producto, o TOTAL).")
        String key,

        @Schema(description = "Etiqueta legible del grupo.")
        String label,

        @Schema(description = "Cantidad total en stock en unidad base (escala 3).")
        BigDecimal qty,

        @Schema(description = "Valorización a costo promedio ponderado (escala 2).")
        BigDecimal valuation,

        @Schema(description = "Porcentaje respecto al total valorizado (escala 2).")
        BigDecimal pct
) {
}
