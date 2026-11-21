package cl.minimarket.pos.reporting.shrinkage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila de los reportes de merma y top de productos con mayor merma ({@code API.md} F.3,
 * {@code HU-MER-04}, {@code HU-MER-05}).
 *
 * <p>Contabiliza únicamente mermas en estado {@code APPROVED}.
 *
 * @param key        identificador del grupo (tipo de merma, id de producto/categoría/local, mes
 *                   {@code YYYY-MM} o {@code TOTAL})
 * @param label      etiqueta legible del grupo
 * @param qty        cantidad total mermada en unidad base (escala 3)
 * @param cost       costo total de merma acumulado (escala 2)
 * @param pctOfSales porcentaje respecto a las ventas netas del mismo filtro ({@code cost / netSales × 100},
 *                   escala 2)
 * @param count      cantidad de registros de merma aprobados
 */
@Schema(name = "ShrinkageReportRow", description = "Fila del reporte de mermas.")
public record ShrinkageReportRow(
        @Schema(description = "Clave de agrupación (tipo, id de entidad, mes YYYY-MM o TOTAL).")
        String key,

        @Schema(description = "Etiqueta legible del grupo.")
        String label,

        @Schema(description = "Cantidad total mermada (escala 3).")
        BigDecimal qty,

        @Schema(description = "Costo total de merma aprobada (escala 2).")
        BigDecimal cost,

        @Schema(description = "Porcentaje del costo de merma sobre ventas netas del mismo filtro (escala 2).")
        BigDecimal pctOfSales,

        @Schema(description = "Número de registros de merma aprobados.")
        long count
) {
}
