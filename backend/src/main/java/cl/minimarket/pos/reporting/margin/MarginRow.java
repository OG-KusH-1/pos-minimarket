package cl.minimarket.pos.reporting.margin;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Fila del reporte de margen y utilidad ({@code API.md} F.3, {@code HU-REP-04}).
 *
 * <p>El margen bruto corresponde a {@code netSales − cost} usando el costo histórico guardado como
 * snapshot en cada línea de venta (neto de devoluciones y anulaciones).
 *
 * @param key         identificador de la agrupación (id de producto/categoría/marca, {@code NONE} si
 *                    no tiene marca, fecha {@code YYYY-MM-DD} o {@code TOTAL})
 * @param label       nombre legible de la agrupación
 * @param qty         cantidad neta vendida (escala 3)
 * @param netSales    ventas netas sin impuestos en CLP (escala 0; incluye porción afecta y exenta)
 * @param cost        costo total neto de la línea en el momento de la venta (escala 2)
 * @param grossMargin utilidad bruta {@code netSales − cost} (escala 2)
 * @param marginPct   margen porcentual {@code grossMargin / netSales × 100} (escala 2)
 */
@Schema(name = "MarginRow", description = "Fila del reporte de margen y utilidad.")
public record MarginRow(
        @Schema(description = "Clave de agrupación (id de entidad, fecha YYYY-MM-DD o TOTAL).")
        String key,

        @Schema(description = "Etiqueta legible del grupo.")
        String label,

        @Schema(description = "Cantidad neta vendida (descontadas devoluciones).")
        BigDecimal qty,

        @Schema(description = "Venta neta sin impuestos (CLP, escala 0).")
        BigDecimal netSales,

        @Schema(description = "Costo snapshot acumulado (escala 2).")
        BigDecimal cost,

        @Schema(description = "Margen bruto (netSales - cost, escala 2).")
        BigDecimal grossMargin,

        @Schema(description = "Porcentaje de margen sobre venta neta (escala 2).")
        BigDecimal marginPct
) {
}
