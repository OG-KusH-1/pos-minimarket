package cl.minimarket.pos.shared.export;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Escritor de archivos XLSX en modo streaming (ADR-08), apto para reportes grandes.
 *
 * <p>Usa {@link SXSSFWorkbook} con una ventana acotada de filas en memoria
 * ({@value #ROW_ACCESS_WINDOW}): las filas anteriores se vuelcan a archivos temporales, que se
 * eliminan en {@link #close()} ({@code dispose}). Por eso <b>siempre</b> debe usarse en un
 * {@code try-with-resources}.
 *
 * <h2>Uso</h2>
 * <pre>{@code
 * try (XlsxWriter xlsx = new XlsxWriter(out, "Productos", List.of(
 *         XlsxWriter.Column.text("Nombre"),
 *         XlsxWriter.Column.clp("Precio"),
 *         XlsxWriter.Column.quantity("Stock")))) {
 *     xlsx.writeRow("Leche", new BigDecimal("1290"), new BigDecimal("12.500"));
 *     xlsx.finish();   // escribe el libro en 'out'
 * }
 * }</pre>
 *
 * <h2>Decisiones de diseño</h2>
 * <ul>
 *   <li><b>Tipado por columna</b>: el {@link ColumnType} fija el formato de número/fecha; el valor se
 *       escribe con su tipo nativo de Excel, de modo que un monto negativo es un número y nunca se
 *       sanea.</li>
 *   <li><b>Sanitización</b> de texto contra inyección de fórmulas mediante {@link FormulaSanitizer}.</li>
 *   <li><b>Dinero sin punto flotante en la API</b>: se reciben {@link BigDecimal}. POI solo acepta
 *       {@code double} para celdas numéricas, por lo que la conversión ocurre en una variable local
 *       al escribir la celda; es exacta para CLP (enteros hasta 2^53) y para cantidades de 3
 *       decimales en rangos de inventario. El valor exacto de referencia sigue siendo la base de datos.</li>
 *   <li><b>Fechas</b>: los instantes se convierten a {@code America/Santiago}.</li>
 *   <li>El ancho de columna es fijo por tipo (o el indicado); no se usa autoajuste porque en streaming
 *       obligaría a rastrear todas las filas.</li>
 *   <li>El flujo de salida pertenece al llamador: {@link #finish()} lo vacía pero no lo cierra.</li>
 * </ul>
 *
 * <p>Punto de extensión: para nuevos tipos de celda, agregar un valor a {@link ColumnType} y una rama
 * en {@code writeCell}.
 *
 * <p>No es seguro para uso concurrente.
 */
public final class XlsxWriter implements AutoCloseable {

    /** Filas que SXSSF mantiene en memoria antes de volcarlas a disco. */
    static final int ROW_ACCESS_WINDOW = 100;

    private static final int MAX_CELL_TEXT_LENGTH = 32_767;
    private static final int MAX_COLUMN_WIDTH_CHARS = 255;
    private static final String CLP_FORMAT = "#,##0";
    private static final String QUANTITY_FORMAT = "#,##0.000";
    private static final String DATE_FORMAT = "yyyy-mm-dd";
    private static final String DATE_TIME_FORMAT = "yyyy-mm-dd hh:mm:ss";
    private static final String INVALID_SHEET_CHARS = "[]:*?/\\";

    /** Tipo de dato de una columna; determina el formato de celda. */
    public enum ColumnType {
        /** Texto (se sanea contra fórmulas). */
        TEXT(30),
        /** Monto en pesos chilenos, formato {@code #,##0} (escala 0). */
        CLP(14),
        /** Cantidad con 3 decimales, formato {@code #,##0.000}. */
        QUANTITY(14),
        /** Fecha pura ({@link LocalDate}). */
        DATE(12),
        /** Fecha y hora, mostrada en hora de negocio. */
        DATETIME(20);

        private final int defaultWidth;

        ColumnType(int defaultWidth) {
            this.defaultWidth = defaultWidth;
        }
    }

    /**
     * Definición de una columna.
     *
     * @param header encabezado (se escribe en negrita y se sanea como texto); no vacío
     * @param type   tipo de dato
     * @param width  ancho en caracteres; {@code 0} usa el ancho por defecto del tipo, máximo 255
     */
    public record Column(String header, ColumnType type, int width) {

        public Column {
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException("El encabezado de columna es obligatorio");
            }
            Objects.requireNonNull(type, "type");
            if (width < 0 || width > MAX_COLUMN_WIDTH_CHARS) {
                throw new IllegalArgumentException("Ancho de columna fuera de rango (0-255): " + width);
            }
        }

        public static Column text(String header) {
            return new Column(header, ColumnType.TEXT, 0);
        }

        public static Column clp(String header) {
            return new Column(header, ColumnType.CLP, 0);
        }

        public static Column quantity(String header) {
            return new Column(header, ColumnType.QUANTITY, 0);
        }

        public static Column date(String header) {
            return new Column(header, ColumnType.DATE, 0);
        }

        public static Column dateTime(String header) {
            return new Column(header, ColumnType.DATETIME, 0);
        }

        int effectiveWidth() {
            return width == 0 ? type.defaultWidth : width;
        }
    }

    private final OutputStream out;
    private final List<Column> columns;
    private final SXSSFWorkbook workbook;
    private final SXSSFSheet sheet;
    private final CellStyle clpStyle;
    private final CellStyle quantityStyle;
    private final CellStyle dateStyle;
    private final CellStyle dateTimeStyle;
    private int nextRowIndex;
    private boolean finished;

    /**
     * Crea el libro, la hoja y la fila de encabezados (en negrita, congelada).
     *
     * @param out       destino del archivo; lo cierra el llamador
     * @param sheetName nombre de la hoja; se limpia de caracteres no permitidos y se trunca a 31
     * @param columns   columnas, al menos una
     * @throws IllegalArgumentException si {@code columns} está vacío
     */
    public XlsxWriter(OutputStream out, String sheetName, List<Column> columns) {
        this.out = Objects.requireNonNull(out, "out");
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos una columna");
        }
        this.columns = List.copyOf(columns);
        this.workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
        // Los temporales de SXSSF pueden contener datos sensibles: se comprimen para reducir su tamaño.
        this.workbook.setCompressTempFiles(true);
        try {
            this.sheet = workbook.createSheet(safeSheetName(sheetName));
            CreationHelper helper = workbook.getCreationHelper();
            this.clpStyle = numberStyle(helper.createDataFormat().getFormat(CLP_FORMAT));
            this.quantityStyle = numberStyle(helper.createDataFormat().getFormat(QUANTITY_FORMAT));
            this.dateStyle = numberStyle(helper.createDataFormat().getFormat(DATE_FORMAT));
            this.dateTimeStyle = numberStyle(helper.createDataFormat().getFormat(DATE_TIME_FORMAT));
            writeHeader();
        } catch (RuntimeException e) {
            workbook.dispose();
            throw e;
        }
    }

    /**
     * Escribe una fila de datos.
     *
     * @param values un valor por columna, en orden: {@code String} (o cualquier objeto, vía
     *               {@code toString}) para TEXT; {@link BigDecimal}/{@link Integer}/{@link Long} para
     *               CLP y QUANTITY; {@link LocalDate} para DATE; {@code LocalDateTime}, {@code Instant},
     *               {@code OffsetDateTime} o {@code ZonedDateTime} para DATETIME. {@code null} deja la
     *               celda vacía.
     * @throws IllegalArgumentException si la cantidad o el tipo de valores no coincide con las columnas
     * @throws IllegalStateException    si ya se llamó a {@link #finish()} o se supera el máximo de filas de Excel
     */
    public void writeRow(Object... values) {
        writeRow(java.util.Arrays.asList(values));
    }

    /** Variante de {@link #writeRow(Object...)} que recibe una lista (admite {@code null}). */
    public void writeRow(List<?> values) {
        ensureWritable();
        Objects.requireNonNull(values, "values");
        if (values.size() != columns.size()) {
            throw new IllegalArgumentException(
                    "Se esperaban " + columns.size() + " valores y llegaron " + values.size());
        }
        Row row = sheet.createRow(nextRowIndex++);
        for (int i = 0; i < columns.size(); i++) {
            Object value = values.get(i);
            if (value != null) {
                writeCell(row.createCell(i), columns.get(i).type(), value);
            }
        }
    }

    /**
     * Número de filas de datos escritas hasta ahora (sin contar el encabezado).
     */
    public int dataRowCount() {
        return nextRowIndex - 1;
    }

    /**
     * Escribe el libro completo en el flujo de salida y lo vacía. Idempotente en el sentido de que
     * una segunda llamada lanza {@link IllegalStateException}.
     *
     * @throws UncheckedIOException si falla la escritura
     */
    public void finish() {
        ensureWritable();
        finished = true;
        try {
            workbook.write(out);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el archivo XLSX", e);
        }
    }

    /** Elimina los archivos temporales de SXSSF y cierra el libro. No cierra el flujo de salida. */
    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo cerrar el libro XLSX", e);
        } finally {
            workbook.dispose();
        }
    }

    private void ensureWritable() {
        if (finished) {
            throw new IllegalStateException("El archivo ya fue finalizado");
        }
        if (nextRowIndex >= SpreadsheetVersion.EXCEL2007.getMaxRows()) {
            throw new IllegalStateException("Se superó el máximo de filas de una hoja de Excel");
        }
    }

    private void writeHeader() {
        CellStyle headerStyle = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        headerStyle.setFont(bold);
        Row header = sheet.createRow(nextRowIndex++);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(truncate(FormulaSanitizer.sanitize(columns.get(i).header())));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, columns.get(i).effectiveWidth() * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    private void writeCell(Cell cell, ColumnType type, Object value) {
        switch (type) {
            case TEXT -> cell.setCellValue(truncate(FormulaSanitizer.sanitize(value.toString())));
            case CLP -> numeric(cell, value, clpStyle);
            case QUANTITY -> numeric(cell, value, quantityStyle);
            case DATE -> {
                if (!(value instanceof LocalDate date)) {
                    throw new IllegalArgumentException(
                            "Se esperaba LocalDate y llegó " + ExportFormats.typeName(value));
                }
                cell.setCellValue(date);
                cell.setCellStyle(dateStyle);
            }
            case DATETIME -> {
                cell.setCellValue(ExportFormats.toBusinessDateTime(value));
                cell.setCellStyle(dateTimeStyle);
            }
        }
    }

    private static void numeric(Cell cell, Object value, CellStyle style) {
        BigDecimal amount = ExportFormats.toBigDecimal(value);
        cell.setCellValue(amount.doubleValue());
        cell.setCellStyle(style);
    }

    private CellStyle numberStyle(short format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(format);
        return style;
    }

    private static String truncate(String text) {
        return text.length() > MAX_CELL_TEXT_LENGTH ? text.substring(0, MAX_CELL_TEXT_LENGTH) : text;
    }

    private static String safeSheetName(String name) {
        String base = (name == null || name.isBlank()) ? "Hoja1" : name.strip();
        StringBuilder clean = new StringBuilder();
        for (char c : base.toCharArray()) {
            clean.append(INVALID_SHEET_CHARS.indexOf(c) >= 0 ? '_' : c);
        }
        String result = clean.length() > 31 ? clean.substring(0, 31) : clean.toString();
        // Excel no admite apóstrofo al inicio ni al final del nombre de hoja.
        return result.replaceAll("^'+|'+$", "_");
    }
}
