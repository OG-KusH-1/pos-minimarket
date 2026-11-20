package cl.minimarket.pos.reporting.shared;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Envoltorio común de todas las respuestas de reportes (API.md F.3):
 * {@code { filters, generatedAt, rows, totals }}.
 *
 * <p>{@code filters} repite el filtro ya validado ({@link ReportFilter#requireRange()} o
 * {@link ReportFilter#optionalRange()}, según el reporte) para que el consumidor sepa con qué
 * criterios se generaron las filas; {@code generatedAt} es el instante UTC de la consulta, que el
 * servicio toma del {@code BusinessClock} ({@code businessClock.now()}); {@code rows} son las filas
 * del reporte y {@code totals} una fila resumen del mismo tipo con las sumas (o {@code null} si el
 * reporte no la calcula).
 *
 * <p>El registro copia defensivamente {@code rows} y acepta una lista nula (se normaliza a vacía),
 * porque los servicios arman las filas de forma incremental.
 *
 * @param filters     filtro con el que se generó el reporte
 * @param generatedAt instante UTC de generación (tipo {@code datetime} de F.3)
 * @param rows        filas del reporte; nunca {@code null} (una lista nula se normaliza a vacía)
 * @param totals      fila de totales del mismo tipo que {@code rows}, o {@code null} si no aplica
 * @param <T>         tipo de fila del reporte (p. ej. {@code SalesReportRow} en T84)
 */
public record ReportResponse<T>(ReportFilter filters, Instant generatedAt, List<T> rows, T totals) {

    public ReportResponse {
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(generatedAt, "generatedAt");
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
