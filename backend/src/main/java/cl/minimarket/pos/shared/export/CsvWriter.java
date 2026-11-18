package cl.minimarket.pos.shared.export;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Escritor de CSV en UTF-8 con BOM, pensado para abrirse directamente en Excel (ADR-08).
 *
 * <h2>Uso</h2>
 * <pre>{@code
 * try (CsvWriter csv = new CsvWriter(out, List.of("Nombre", "Precio"))) {
 *     csv.writeRow("Leche", new BigDecimal("1290"));
 * }
 * }</pre>
 *
 * <h2>Decisiones de diseño</h2>
 * <ul>
 *   <li><b>BOM UTF-8</b> al inicio: sin él Excel asume ANSI y deforma tildes y la ñ.</li>
 *   <li><b>Separador {@code ;} por defecto</b>: con la configuración regional chilena (coma decimal)
 *       Excel usa punto y coma como separador de lista; con {@code ,} abriría todo en una columna.
 *       Es configurable (constructor con {@code delimiter}) para integraciones que esperen coma o tabulación.</li>
 *   <li><b>Fin de línea CRLF</b> (RFC 4180).</li>
 *   <li><b>Tipado por valor</b>: {@link String} se sanea con {@link FormulaSanitizer}; los números
 *       ({@link BigDecimal}, {@link Integer}, {@link Long}) se escriben con {@code toPlainString} y
 *       <b>no</b> se alteran (un {@code -5} sigue siendo número); las fechas en ISO y los instantes
 *       en hora {@code America/Santiago}. Los decimales usan punto: el CSV está pensado también como
 *       formato de intercambio reimportable con {@link CsvXlsxReader}, no como vista final; para
 *       una vista formateada para humanos use {@link XlsxWriter}.</li>
 *   <li>El flujo de salida pertenece al llamador: {@link #close()} vacía los buffers pero no lo cierra.</li>
 * </ul>
 *
 * <p>No es seguro para uso concurrente.
 */
public final class CsvWriter implements AutoCloseable {

    /** Separador por defecto (configuración regional chilena). */
    public static final char DEFAULT_DELIMITER = ';';

    private static final String BOM = "﻿";

    private final CSVPrinter printer;
    private final int columnCount;

    /** Crea el escritor con el separador {@value #DEFAULT_DELIMITER} y escribe BOM y encabezados. */
    public CsvWriter(OutputStream out, List<String> headers) {
        this(out, headers, DEFAULT_DELIMITER);
    }

    /**
     * Crea el escritor y escribe de inmediato el BOM y la fila de encabezados.
     *
     * @param out       destino; lo cierra el llamador
     * @param headers   encabezados, al menos uno, ninguno vacío (se sanean igual que el texto)
     * @param delimiter separador de campos; no puede ser comilla, CR ni LF
     * @throws IllegalArgumentException si los encabezados o el separador son inválidos
     * @throws UncheckedIOException     si falla la escritura inicial
     */
    public CsvWriter(OutputStream out, List<String> headers, char delimiter) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(headers, "headers");
        if (headers.isEmpty() || headers.stream().anyMatch(h -> h == null || h.isBlank())) {
            throw new IllegalArgumentException("Los encabezados son obligatorios y no pueden estar vacíos");
        }
        if (delimiter == '"' || delimiter == '\r' || delimiter == '\n') {
            throw new IllegalArgumentException("Separador no permitido");
        }
        this.columnCount = headers.size();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setRecordSeparator("\r\n")
                .get();
        try {
            OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write(BOM);
            this.printer = new CSVPrinter(writer, format);
            printer.printRecord(headers.stream().map(FormulaSanitizer::sanitize).toList());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el CSV", e);
        }
    }

    /**
     * Escribe una fila.
     *
     * @param values un valor por columna: {@code String}, {@link BigDecimal}, {@link Integer},
     *               {@link Long}, {@link LocalDate}, fecha-hora ({@code LocalDateTime}, {@code Instant},
     *               {@code OffsetDateTime}, {@code ZonedDateTime}); {@code null} produce campo vacío.
     * @throws IllegalArgumentException si la cantidad o el tipo de valores es inválido
     */
    public void writeRow(Object... values) {
        writeRow(Arrays.asList(values));
    }

    /** Variante de {@link #writeRow(Object...)} que recibe una lista. */
    public void writeRow(List<?> values) {
        Objects.requireNonNull(values, "values");
        if (values.size() != columnCount) {
            throw new IllegalArgumentException(
                    "Se esperaban " + columnCount + " valores y llegaron " + values.size());
        }
        List<String> fields = new ArrayList<>(columnCount);
        for (Object value : values) {
            fields.add(format(value));
        }
        try {
            printer.printRecord(fields);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el CSV", e);
        }
    }

    /** Vacía los buffers hacia el flujo de salida (que sigue abierto). */
    @Override
    public void close() {
        try {
            printer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo vaciar el CSV", e);
        }
    }

    private static String format(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof LocalDate date) {
            return ExportFormats.formatDate(date);
        }
        if (ExportFormats.isDateTime(value)) {
            return ExportFormats.DATE_TIME.format(ExportFormats.toBusinessDateTime(value));
        }
        return FormulaSanitizer.sanitize(value.toString());
    }
}
