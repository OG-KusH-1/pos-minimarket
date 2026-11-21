package cl.minimarket.pos.reporting.cash;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Fila del reporte de diferencias de caja ({@code API.md} F.3, {@code HU-REP-09}).
 *
 * @param sessionId    identificador del turno de caja ({@code null} en la fila de totales)
 * @param number       folio correlativo del turno (o {@code TOTAL})
 * @param businessDate fecha de negocio del turno ({@code null} en la fila de totales)
 * @param cashier      nombre del cajero del turno
 * @param terminal     nombre de la terminal POS
 * @param expected     monto total esperado al cierre (CLP, escala 0)
 * @param counted      monto total contado en arqueo (CLP, escala 0)
 * @param difference   diferencia {@code counted − expected} (CLP, escala 0)
 * @param approvedBy   nombre del supervisor que aprobó la diferencia, o {@code null} si no fue
 *                     aprobada
 */
@Schema(name = "CashDifferenceRow", description = "Fila del reporte de diferencias de caja.")
public record CashDifferenceRow(
        @Schema(description = "Identificador del turno de caja (null en la fila de totales).")
        Long sessionId,

        @Schema(description = "Número correlativo del turno de caja (o TOTAL).")
        String number,

        @Schema(description = "Fecha de negocio del turno (YYYY-MM-DD).")
        LocalDate businessDate,

        @Schema(description = "Nombre del cajero.")
        String cashier,

        @Schema(description = "Nombre de la terminal.")
        String terminal,

        @Schema(description = "Total esperado al cierre (CLP, escala 0).")
        BigDecimal expected,

        @Schema(description = "Total contado en arqueo (CLP, escala 0).")
        BigDecimal counted,

        @Schema(description = "Diferencia de cierre counted - expected (CLP, escala 0).")
        BigDecimal difference,

        @Schema(description = "Nombre de quien aprobó la diferencia, si aplica.")
        String approvedBy
) {
}
