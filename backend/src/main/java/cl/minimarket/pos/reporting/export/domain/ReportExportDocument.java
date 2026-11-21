package cl.minimarket.pos.reporting.export.domain;

import cl.minimarket.pos.shared.export.XlsxWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Modelo intermedio genérico que representa cualquier reporte exportable hacia XLSX (vía
 * {@link XlsxWriter}) o PDF (vía {@code templates/pdf/report.html} y {@code PdfRenderer}).
 *
 * <p>Permite unificar la infraestructura de renderizado para reportes tabulares de una tabla y para
 * el dashboard multi-sección sin duplicar controladores de plantilla ni escritores Excel.
 *
 * @param type           tipo de reporte exportado
 * @param title          título del documento
 * @param subtitle       subtítulo descriptivo opcional (ej. criterio de agrupación)
 * @param filters        pares etiqueta/valor de los filtros aplicados (mostrados en PDF)
 * @param kpis           indicadores resumidos opcionales (usados en Dashboard PDF)
 * @param sections       secciones tabulares renderizadas en el PDF
 * @param xlsxColumns    columnas tipadas de la hoja XLSX
 * @param xlsxRows       filas de datos de la hoja XLSX (incluye la fila de totales si aplica)
 * @param dataRowCount   cantidad de filas de datos exportadas (sin contar totales) para auditoría
 */
public record ReportExportDocument(
        ReportExportType type,
        String title,
        String subtitle,
        List<LabelValue> filters,
        List<LabelValue> kpis,
        List<ExportSection> sections,
        List<XlsxWriter.Column> xlsxColumns,
        List<List<Object>> xlsxRows,
        int dataRowCount
) {

    private static final String DEFAULT_EMPTY_MESSAGE = "Sin datos para los filtros seleccionados.";

    public ReportExportDocument {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        filters = filters == null ? List.of() : List.copyOf(filters);
        kpis = kpis == null ? List.of() : List.copyOf(kpis);
        sections = sections == null ? List.of() : List.copyOf(sections);
        xlsxColumns = xlsxColumns == null ? List.of() : List.copyOf(xlsxColumns);
        xlsxRows = xlsxRows == null ? List.of()
                : xlsxRows.stream().map(ReportExportDocument::immutableNullableList).toList();
        // Nota: las filas admiten celdas nulas (p. ej. fechas o totales vacíos en XLSX).
    }

    private static <T> List<T> immutableNullableList(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    /**
     * Crea un documento de exportación estándar con una sola sección tabular compartida entre PDF y
     * XLSX.
     */
    public static ReportExportDocument singleTable(
            ReportExportType type,
            String subtitle,
            List<LabelValue> filters,
            List<PdfColumn> pdfColumns,
            List<List<String>> pdfRows,
            List<String> pdfTotals,
            List<XlsxWriter.Column> xlsxColumns,
            List<List<Object>> xlsxRows,
            int dataRowCount) {
        ExportSection section = new ExportSection(null, pdfColumns, pdfRows, pdfTotals, DEFAULT_EMPTY_MESSAGE);
        return new ReportExportDocument(
                type,
                type.defaultTitle(),
                subtitle,
                filters,
                List.of(),
                List.of(section),
                xlsxColumns,
                xlsxRows,
                dataRowCount);
    }

    /** Par etiqueta-valor para metadatos de filtros y KPIs del PDF. */
    public record LabelValue(String label, String value) {
    }

    /** Definición de columna de tabla PDF con alineación numérica opcional. */
    public record PdfColumn(String header, boolean numeric) {

        public static PdfColumn text(String header) {
            return new PdfColumn(header, false);
        }

        public static PdfColumn num(String header) {
            return new PdfColumn(header, true);
        }
    }

    /** Sección tabular dentro de la plantilla genérica {@code templates/pdf/report.html}. */
    public record ExportSection(
            String heading,
            List<PdfColumn> columns,
            List<List<String>> rows,
            List<String> totals,
            String emptyMessage
    ) {

        public ExportSection {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of()
                    : rows.stream().map(ReportExportDocument::immutableNullableList).toList();
            totals = totals == null ? null : immutableNullableList(totals);
            emptyMessage = (emptyMessage == null || emptyMessage.isBlank())
                    ? DEFAULT_EMPTY_MESSAGE
                    : emptyMessage;
        }
    }
}
