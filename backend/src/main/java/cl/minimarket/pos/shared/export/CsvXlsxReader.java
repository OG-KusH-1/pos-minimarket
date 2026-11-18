package cl.minimarket.pos.shared.export;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lector de archivos de importación CSV o XLSX hacia filas {@code Map<encabezado, valor>}.
 *
 * <h2>Uso</h2>
 * <pre>{@code
 * CsvXlsxReader reader = new CsvXlsxReader();                 // límites por defecto
 * reader.read(inputStream, "productos.xlsx", row -> {
 *     String sku = row.values().get("SKU");                    // row.rowNumber() sirve para reportar errores
 * });
 * }</pre>
 *
 * <h2>Decisiones de diseño</h2>
 * <ul>
 *   <li><b>Detección y rechazo</b>: se decide por contenido (magic bytes {@code PK\u0003\u0004} =
 *       XLSX) y se contrasta con la extensión; si no coinciden, o la extensión no es {@code .csv} /
 *       {@code .xlsx}, o un "CSV" contiene bytes NUL (binario), se lanza
 *       {@link InvalidImportFileException}. Nunca se confía solo en la extensión.</li>
 *   <li><b>Límites</b>: tamaño máximo en bytes (se lee con tope, nunca el flujo completo sin límite)
 *       y máximo de filas de datos. POI aplica además su protección contra zip-bombs.</li>
 *   <li><b>Número de fila</b>: {@link ImportRow#rowNumber()} es el de la fila en el archivo, con el
 *       encabezado como fila 1 (en XLSX coincide con el número que ve el usuario en Excel). En CSV es
 *       el número de registro, de modo que un campo con saltos de línea entre comillas cuenta una vez.
 *       Las filas totalmente vacías se omiten pero conservan su numeración.</li>
 *   <li><b>CSV</b>: el separador ({@code ;}, {@code ,} o tabulación) se detecta en la línea de
 *       encabezados salvo que se fije explícitamente; se ignora el BOM; se decodifica como UTF-8 y, si
 *       el archivo no es UTF-8 válido, como Windows-1252 (CSV "ANSI" típico de Excel).</li>
 *   <li><b>XLSX</b>: se lee la primera hoja en streaming (API de eventos de POI sobre XSSF, ADR-08): la memoria no crece con el número de filas y se corta al superar {@code maxRows}. Los números se
 *       entregan como texto plano sin notación científica ni separadores; las fechas como
 *       {@code yyyy-MM-dd} (o con hora si la tiene); las fórmulas, por su último valor calculado, sin
 *       evaluarse (no se ejecuta nada del archivo).</li>
 *   <li>Los encabezados se recortan; los vacíos se ignoran; los duplicados se rechazan. Las celdas
 *       ausentes se entregan como cadena vacía. Si una fila trae más valores que encabezados, el
 *       excedente se descarta.</li>
 * </ul>
 *
 * <p>La instancia es inmutable y segura para uso concurrente.
 */
public final class CsvXlsxReader {

    /** Máximo de filas de datos por defecto. */
    public static final int DEFAULT_MAX_ROWS = 100_000;

    /** Tamaño máximo por defecto (20 MB, límite de importación del plan). */
    public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final char[] CANDIDATE_DELIMITERS = {';', ',', '\t'};
    /** Tope de bytes descomprimidos por entrada del ZIP (global de POI; el ratio mínimo 0.01 se mantiene). */
    private static final long MAX_ENTRY_SIZE = 256L * 1024 * 1024;

    static {
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE);
    }

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /**
     * Fila leída.
     *
     * @param rowNumber número de fila en el archivo (encabezado = 1); úselo en los mensajes de error
     * @param values    valores por encabezado, en el orden de las columnas; nunca {@code null}
     */
    public record ImportRow(int rowNumber, Map<String, String> values) {
    }

    private final int maxRows;
    private final long maxBytes;
    private final Character forcedDelimiter;

    /** Lector con los límites por defecto y detección automática de separador. */
    public CsvXlsxReader() {
        this(DEFAULT_MAX_ROWS, DEFAULT_MAX_BYTES, null);
    }

    /** Lector con límites propios y detección automática de separador. */
    public CsvXlsxReader(int maxRows, long maxBytes) {
        this(maxRows, maxBytes, null);
    }

    /**
     * @param maxRows         máximo de filas de datos (sin encabezado); positivo
     * @param maxBytes        máximo de bytes del archivo; positivo
     * @param forcedDelimiter separador CSV fijo, o {@code null} para detectarlo
     * @throws IllegalArgumentException si algún límite no es positivo
     */
    public CsvXlsxReader(int maxRows, long maxBytes, Character forcedDelimiter) {
        if (maxRows <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("Los límites deben ser positivos");
        }
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
        this.forcedDelimiter = forcedDelimiter;
    }

    /**
     * Lee todas las filas en una lista. Para archivos grandes prefiera
     * {@link #read(InputStream, String, Consumer)}.
     *
     * @see #read(InputStream, String, Consumer)
     */
    public List<ImportRow> readAll(InputStream in, String fileName) {
        List<ImportRow> rows = new ArrayList<>();
        read(in, fileName, rows::add);
        return rows;
    }

    /**
     * Lee el archivo y entrega cada fila de datos al consumidor, en orden.
     *
     * @param in       contenido del archivo; lo cierra el llamador
     * @param fileName nombre original (solo se usa su extensión)
     * @param consumer receptor de filas
     * @throws InvalidImportFileException si no es CSV/XLSX, está corrupto, no tiene encabezados,
     *                                    tiene encabezados duplicados o supera los límites
     */
    public void read(InputStream in, String fileName, Consumer<ImportRow> consumer) {
        byte[] content = readBounded(in);
        String extension = extensionOf(fileName);
        boolean zip = startsWith(content, ZIP_MAGIC);
        if (zip && "xlsx".equals(extension)) {
            readXlsx(content, consumer);
        } else if (!zip && "csv".equals(extension)) {
            readCsv(content, consumer);
        } else {
            throw new InvalidImportFileException(
                    "Archivo no soportado: se aceptan solo CSV o XLSX cuyo contenido coincida con su extensión");
        }
    }

    // ---------------------------------------------------------------- CSV

    private void readCsv(byte[] content, Consumer<ImportRow> consumer) {
        for (byte b : content) {
            if (b == 0) {
                throw new InvalidImportFileException("El archivo CSV contiene datos binarios");
            }
        }
        String text = stripBom(decode(content));
        if (text.isBlank()) {
            throw new InvalidImportFileException("El archivo está vacío");
        }
        char delimiter = forcedDelimiter != null ? forcedDelimiter : detectDelimiter(text);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            List<String> headers = null;
            int dataRows = 0;
            for (CSVRecord record : parser) {
                if (headers == null) {
                    headers = validatedHeaders(record.toList());
                    continue;
                }
                List<String> cells = record.toList();
                if (isBlankRow(cells)) {
                    continue;
                }
                dataRows = countRow(dataRows);
                deliver(consumer, new ImportRow((int) record.getRecordNumber(), toMap(headers, cells)));
            }
            if (headers == null) {
                throw new InvalidImportFileException("El archivo no tiene encabezados");
            }
        } catch (IOException | RuntimeException e) {
            throw wrap(e, "El archivo CSV está mal formado");
        }
    }

    /** UTF-8 estricto; si hay bytes inválidos se asume Windows-1252. */
    private static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, WINDOWS_1252);
        }
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }

    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String firstLine = end < 0 ? text : text.substring(0, end);
        char best = CANDIDATE_DELIMITERS[0];
        long bestCount = 0;
        for (char candidate : CANDIDATE_DELIMITERS) {
            long count = firstLine.chars().filter(c -> c == candidate).count();
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    // --------------------------------------------------------------- XLSX

    /**
     * Lee la primera hoja en streaming (API de eventos de POI): nunca se materializa el libro, de
     * modo que la memoria no depende del número de filas y {@code maxRows} corta la lectura apenas
     * se supera. El contenido se vuelca a un archivo temporal (ya acotado por {@code maxBytes}) para
     * que POI lea el ZIP desde disco y no copie sus entradas al heap; se borra siempre al terminar.
     */
    private void readXlsx(byte[] content, Consumer<ImportRow> consumer) {
        Path temp = null;
        try {
            temp = Files.createTempFile("import-", ".xlsx");
            Files.write(temp, content);
            try (OPCPackage pkg = OPCPackage.open(temp.toFile(), PackageAccess.READ)) {
                XSSFReader reader = new XSSFReader(pkg);
                SheetContents contents = new SheetContents(consumer);
                XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
                if (!sheets.hasNext()) {
                    throw new InvalidImportFileException("El libro no tiene hojas");
                }
                try (InputStream sheet = sheets.next()) {
                    XMLReader parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new SheetXmlHandler(contents,
                            new ReadOnlySharedStringsTable(pkg), reader.getStylesTable()));
                    parser.parse(new InputSource(sheet));
                }
                if (contents.headers == null) {
                    throw new InvalidImportFileException("El archivo no tiene encabezados");
                }
            }
        } catch (IOException | RuntimeException | OpenXML4JException | SAXException
                 | ParserConfigurationException e) {
            throw wrap(e, "El archivo XLSX está corrupto o no es válido");
        } finally {
            deleteQuietly(temp);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // El temporal queda en el directorio temporal del sistema; no debe enmascarar el resultado.
            }
        }
    }

    /**
     * Manejador SAX propio de la hoja ({@code sheetData}). Se usa en lugar de
     * {@code XSSFSheetXMLHandler} porque éste entrega los números como {@code double} ya formateados
     * "de pantalla" (p. ej. {@code 1,290}); aquí el valor numérico se toma del XML como texto y se
     * convierte con {@link BigDecimal}, sin punto flotante, y solo las fechas pasan por
     * {@code double} dentro de una variable local (lo exige {@link DateUtil}).
     */
    private static final class SheetXmlHandler extends DefaultHandler {
        private final SheetContents contents;
        private final ReadOnlySharedStringsTable strings;
        private final StylesTable styles;
        private final StringBuilder text = new StringBuilder();
        private String reference;
        private String type;
        private int styleIndex;
        private boolean inValue;

        SheetXmlHandler(SheetContents contents, ReadOnlySharedStringsTable strings, StylesTable styles) {
            this.contents = contents;
            this.strings = strings;
            this.styles = styles;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (localName) {
                case "row" -> contents.startRow(Integer.parseInt(attributes.getValue("r")) - 1);
                case "c" -> {
                    reference = attributes.getValue("r");
                    type = attributes.getValue("t");
                    String style = attributes.getValue("s");
                    styleIndex = style == null ? 0 : Integer.parseInt(style);
                    text.setLength(0);
                }
                case "v", "t" -> {
                    inValue = true;
                    text.setLength(0);
                }
                default -> { }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inValue) {
                text.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (localName) {
                case "v", "t" -> inValue = false;
                case "c" -> {
                    if (reference != null) {
                        contents.cell(reference, cellValue());
                    }
                    reference = null;
                }
                case "row" -> contents.endRow();
                default -> { }
            }
        }

        private String cellValue() {
            String raw = text.toString();
            if (type == null || "n".equals(type)) {
                return raw.isBlank() ? "" : numberText(raw.strip());
            }
            return switch (type) {
                case "s" -> strings.getItemAt(Integer.parseInt(raw.strip())).getString();
                case "b" -> "1".equals(raw.strip()) ? "true" : "false";
                case "e" -> "";
                default -> raw; // str (resultado de fórmula) e inlineStr
            };
        }

        private String numberText(String raw) {
            BigDecimal number = new BigDecimal(raw);
            XSSFCellStyle style = styles.getStyleAt(styleIndex);
            if (DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString())) {
                LocalDateTime dateTime = DateUtil.getLocalDateTime(number.doubleValue());
                return dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)
                        ? dateTime.toLocalDate().toString()
                        : ExportFormats.DATE_TIME.format(dateTime);
            }
            return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
        }
    }

    /** Arma cada fila a partir de los eventos de la hoja; la primera fila son los encabezados. */
    private final class SheetContents {
        private final Consumer<ImportRow> consumer;
        private List<String> headers;
        private List<String> cells = new ArrayList<>();
        private int rowNumber;
        private int dataRows;

        SheetContents(Consumer<ImportRow> consumer) {
            this.consumer = consumer;
        }

        void startRow(int rowNum) {
            rowNumber = rowNum + 1;
            cells = new ArrayList<>();
        }

        void cell(String cellReference, String value) {
            int column = new CellReference(cellReference).getCol();
            while (cells.size() < column) {
                cells.add("");
            }
            cells.add(value);
        }

        void endRow() {
            if (headers == null) {
                headers = validatedHeaders(cells);
            } else if (!isBlankRow(cells)) {
                dataRows = countRow(dataRows);
                deliver(consumer, new ImportRow(rowNumber, toMap(headers, cells)));
            }
        }
    }

    // ------------------------------------------------------------- común

    private byte[] readBounded(InputStream in) {
        if (in == null) {
            throw new InvalidImportFileException("No se recibió el archivo");
        }
        try {
            byte[] content = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
            if (content.length > maxBytes) {
                throw new InvalidImportFileException("El archivo supera el tamaño máximo permitido");
            }
            if (content.length == 0) {
                throw new InvalidImportFileException("El archivo está vacío");
            }
            return content;
        } catch (IOException e) {
            throw new InvalidImportFileException("No se pudo leer el archivo", e);
        }
    }

    private int countRow(int current) {
        if (current >= maxRows) {
            throw new InvalidImportFileException("El archivo supera el máximo de " + maxRows + " filas");
        }
        return current + 1;
    }

    private static List<String> validatedHeaders(List<String> raw) {
        List<String> headers = new ArrayList<>(raw.size());
        for (String header : raw) {
            String trimmed = header == null ? "" : header.strip();
            if (!trimmed.isEmpty() && headers.contains(trimmed)) {
                throw new InvalidImportFileException("Encabezado duplicado: " + trimmed);
            }
            headers.add(trimmed);
        }
        if (headers.stream().allMatch(String::isEmpty)) {
            throw new InvalidImportFileException("El archivo no tiene encabezados");
        }
        return headers;
    }

    private static Map<String, String> toMap(List<String> headers, List<String> cells) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            if (!headers.get(i).isEmpty()) {
                values.put(headers.get(i), i < cells.size() ? cells.get(i) : "");
            }
        }
        return values;
    }

    private static boolean isBlankRow(List<String> cells) {
        return cells.stream().allMatch(c -> c == null || c.isBlank());
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Entrega la fila al consumidor. Sus excepciones se envuelven en {@link ConsumerFailure} para que
     * {@link #wrap} no las confunda con un archivo corrupto y se relancen tal cual al llamador.
     */
    private static void deliver(Consumer<ImportRow> consumer, ImportRow row) {
        try {
            consumer.accept(row);
        } catch (RuntimeException e) {
            throw new ConsumerFailure(e);
        }
    }

    private static RuntimeException wrap(Exception e, String message) {
        if (e instanceof ConsumerFailure failure) {
            return failure.original;
        }
        return e instanceof InvalidImportFileException invalid ? invalid
                : new InvalidImportFileException(message, e);
    }

    /** Transporta una excepción lanzada por el consumidor a través del bloque de captura. */
    private static final class ConsumerFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient RuntimeException original;

        ConsumerFailure(RuntimeException original) {
            super(original);
            this.original = original;
        }
    }
}
