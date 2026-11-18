package cl.minimarket.pos.shared.export;

import cl.minimarket.pos.shared.export.CsvXlsxReader.ImportRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XlsxCsvExportTest {

    private static final List<XlsxWriter.Column> COLUMNS = List.of(
            XlsxWriter.Column.text("Nombre"),
            XlsxWriter.Column.clp("Precio"),
            XlsxWriter.Column.quantity("Stock"),
            XlsxWriter.Column.date("Fecha"));

    private final CsvXlsxReader reader = new CsvXlsxReader();

    // ------------------------------------------------------------ XLSX

    @Test
    void xlsxDe10000FilasSeRelee() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter xlsx = new XlsxWriter(out, "Productos", COLUMNS)) {
            for (int i = 1; i <= 10_000; i++) {
                xlsx.writeRow("Producto Ñandú " + i, new BigDecimal(i * 10), new BigDecimal(i).movePointLeft(3),
                        LocalDate.of(2026, 1, 1).plusDays(i % 365));
            }
            assertThat(xlsx.dataRowCount()).isEqualTo(10_000);
            xlsx.finish();
        }

        List<ImportRow> rows = reader.readAll(new ByteArrayInputStream(out.toByteArray()), "productos.xlsx");

        assertThat(rows).hasSize(10_000);
        assertThat(rows.get(0).rowNumber()).isEqualTo(2);
        assertThat(rows.get(0).values()).containsEntry("Nombre", "Producto Ñandú 1")
                .containsEntry("Precio", "10").containsEntry("Stock", "0.001")
                .containsEntry("Fecha", "2026-01-02");
        ImportRow last = rows.get(9_999);
        assertThat(last.rowNumber()).isEqualTo(10_001);
        assertThat(last.values()).containsEntry("Nombre", "Producto Ñandú 10000")
                .containsEntry("Precio", "100000").containsEntry("Stock", "10");
    }

    @Test
    void xlsxSaneaTextosPeroNoNumerosNiTextoNormal() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter xlsx = new XlsxWriter(out, "Datos", List.of(
                XlsxWriter.Column.text("A"), XlsxWriter.Column.clp("B")))) {
            xlsx.writeRow("=1+1", new BigDecimal("-500"));
            xlsx.writeRow("+x", null);
            xlsx.writeRow("-x", null);
            xlsx.writeRow("@x", null);
            xlsx.writeRow("Leche entera", new BigDecimal("1290"));
            xlsx.finish();
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(texts(sheet.getRow(1))).isEqualTo("'=1+1");
            assertThat(texts(sheet.getRow(2))).isEqualTo("'+x");
            assertThat(texts(sheet.getRow(3))).isEqualTo("'-x");
            assertThat(texts(sheet.getRow(4))).isEqualTo("'@x");
            assertThat(texts(sheet.getRow(5))).isEqualTo("Leche entera");
            Cell negative = sheet.getRow(1).getCell(1);
            assertThat(negative.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(negative.getNumericCellValue()).isEqualTo(-500d);
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFont().getBold()).isTrue();
        }
    }

    @Test
    void xlsxConvierteInstantesAHoraDeChile() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter xlsx = new XlsxWriter(out, "Ventas", List.of(XlsxWriter.Column.dateTime("Cuándo")))) {
            xlsx.writeRow(Instant.parse("2026-01-15T15:00:00Z")); // verano: UTC-3
            xlsx.finish();
        }

        List<ImportRow> rows = reader.readAll(new ByteArrayInputStream(out.toByteArray()), "v.xlsx");

        assertThat(rows.get(0).values()).containsEntry("Cuándo", "2026-01-15 12:00:00");
    }

    @Test
    void xlsxRechazaFilasInconsistentes() {
        try (XlsxWriter xlsx = new XlsxWriter(new ByteArrayOutputStream(), "X", COLUMNS)) {
            assertThatThrownBy(() -> xlsx.writeRow("solo uno")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> xlsx.writeRow("a", "no numero", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> xlsx.writeRow("a", 1.5d, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------- CSV

    @Test
    void csvTieneBomSeparadorPuntoYComaYSanea() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvWriter csv = new CsvWriter(out, List.of("Nombre", "Monto"))) {
            csv.writeRow("=1+1", new BigDecimal("-5"));
            csv.writeRow("+x", new BigDecimal("1290"));
            csv.writeRow("-x", null);
            csv.writeRow("@x", null);
            csv.writeRow("Azúcar; \"fina\"", new BigDecimal("2.500"));
        }

        byte[] bytes = out.toByteArray();
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Nombre;Monto\r\n")
                .contains("'=1+1;-5\r\n", "'+x;1290\r\n", "'-x;\r\n", "'@x;\r\n")
                .contains("\"Azúcar; \"\"fina\"\"\";2.500\r\n");
    }

    @Test
    void csvSeLeeDeVueltaConBomYPuntoYComa() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvWriter csv = new CsvWriter(out, List.of("Nombre", "Monto"))) {
            csv.writeRow("Piña; \"colada\"", new BigDecimal("-5"));
            csv.writeRow("Ñoño", new BigDecimal("10"));
        }

        List<ImportRow> rows = reader.readAll(new ByteArrayInputStream(out.toByteArray()), "datos.CSV");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).rowNumber()).isEqualTo(2);
        assertThat(rows.get(0).values()).containsEntry("Nombre", "Piña; \"colada\"").containsEntry("Monto", "-5");
        assertThat(rows.get(1).values()).containsEntry("Nombre", "Ñoño");
    }

    @Test
    void csvDetectaComaOmiteFilasVaciasYConservaNumeracion() {
        String csv = "sku,nombre\n1,Uno\n\n3,Tres\n";

        List<ImportRow> rows = reader.readAll(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "a.csv");

        assertThat(rows).extracting(ImportRow::rowNumber).containsExactly(2, 4);
        assertThat(rows.get(1).values()).containsEntry("sku", "3").containsEntry("nombre", "Tres");
    }

    @Test
    void csvEnWindows1252SeDecodifica() {
        byte[] ansi = "nombre;x\nAzúcar;1\n".getBytes(java.nio.charset.Charset.forName("windows-1252"));

        List<ImportRow> rows = reader.readAll(new ByteArrayInputStream(ansi), "a.csv");

        assertThat(rows.get(0).values()).containsEntry("nombre", "Azúcar");
    }

    // ----------------------------------------------------------- Lector

    @Test
    void rechazaArchivosQueNoSonCsvNiXlsx() {
        assertThatThrownBy(() -> reader.readAll(stream("hola"), "malo.exe"))
                .isInstanceOf(InvalidImportFileException.class);
        assertThatThrownBy(() -> reader.readAll(stream("hola"), "malo.xlsx"))
                .as("extensión xlsx sin firma ZIP").isInstanceOf(InvalidImportFileException.class);
        assertThatThrownBy(() -> reader.readAll(new ByteArrayInputStream(new byte[]{'a', ';', 0, 1}), "b.csv"))
                .as("binario con extensión csv").isInstanceOf(InvalidImportFileException.class);
        assertThatThrownBy(() -> reader.readAll(new ByteArrayInputStream(new byte[]{'P', 'K', 3, 4, 1}), "c.csv"))
                .as("zip con extensión csv").isInstanceOf(InvalidImportFileException.class);
        assertThatThrownBy(() -> reader.readAll(new ByteArrayInputStream(new byte[]{'P', 'K', 3, 4, 1}), "c.xlsx"))
                .as("zip corrupto").isInstanceOf(InvalidImportFileException.class);
        assertThatThrownBy(() -> reader.readAll(stream(""), "vacio.csv"))
                .isInstanceOf(InvalidImportFileException.class);
    }

    @Test
    void respetaLimiteDeFilasYDeBytesYRechazaEncabezadosDuplicados() {
        CsvXlsxReader small = new CsvXlsxReader(2, 1024);
        assertThat(small.readAll(stream("a\n1\n2\n"), "a.csv")).hasSize(2);
        assertThatThrownBy(() -> small.readAll(stream("a\n1\n2\n3\n"), "a.csv"))
                .isInstanceOf(InvalidImportFileException.class).hasMessageContaining("2 filas");
        assertThatThrownBy(() -> new CsvXlsxReader(10, 5).readAll(stream("a\n1\n2\n3\n"), "a.csv"))
                .isInstanceOf(InvalidImportFileException.class).hasMessageContaining("tamaño");
        assertThatThrownBy(() -> reader.readAll(stream("a;a\n1;2\n"), "a.csv"))
                .isInstanceOf(InvalidImportFileException.class).hasMessageContaining("duplicado");
    }

    @Test
    void lasExcepcionesDelConsumidorSePropaganSinEnvolver() {
        IllegalStateException boom = new IllegalStateException("fila inválida");

        assertThatThrownBy(() -> reader.read(stream("a\n1\n"), "a.csv", row -> {
            throw boom;
        })).isSameAs(boom);
    }

    @Test
    void xlsxDeUnMillonDeFilasSeRechazaPorLimiteSinAgotarMemoria() throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("un-millon-", ".xlsx");
        try {
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file);
                 XlsxWriter xlsx = new XlsxWriter(out, "Datos", List.of(XlsxWriter.Column.text("A")))) {
                for (int i = 0; i < 1_000_000; i++) {
                    xlsx.writeRow("x");
                }
                xlsx.finish();
            }

            try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                assertThatThrownBy(() -> reader.readAll(in, "grande.xlsx"))
                        .isInstanceOf(InvalidImportFileException.class)
                        .hasMessageContaining("filas");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static String texts(Row row) {
        return row.getCell(0).getStringCellValue();
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
