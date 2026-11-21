package cl.minimarket.pos.reporting.export.application;

import cl.minimarket.pos.reporting.cash.CashDifferenceRow;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.AlertsView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.HourlySalesView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.PaymentMixView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.TopProductView;
import cl.minimarket.pos.reporting.export.domain.ExportFormat;
import cl.minimarket.pos.reporting.export.domain.ExportedReport;
import cl.minimarket.pos.reporting.export.domain.ReportExportDocument;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.export.CsvXlsxReader;
import cl.minimarket.pos.shared.export.CsvXlsxReader.ImportRow;
import cl.minimarket.pos.shared.export.PdfRenderer;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de {@link ReportExportRenderer}, {@link ReportExportDocumentMapper} y
 * {@link ExportFormat} (T86).
 */
class ReportExportRendererTest {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final Instant FIXED_NOW = Instant.parse("2026-06-15T16:30:00Z");
    private static final BusinessClock FIXED_CLOCK =
            new BusinessClock(Clock.fixed(FIXED_NOW, CHILE), CHILE);

    private final ReportExportRenderer renderer =
            new ReportExportRenderer(new PdfRenderer(Clock.fixed(FIXED_NOW, CHILE)), FIXED_CLOCK);

    @Test
    void formatParamDefaultsToXlsxAndRejectsUnsupportedFormats() {
        assertThat(ExportFormat.fromParam(null)).isEqualTo(ExportFormat.XLSX);
        assertThat(ExportFormat.fromParam("  ")).isEqualTo(ExportFormat.XLSX);
        assertThat(ExportFormat.fromParam("XLSX")).isEqualTo(ExportFormat.XLSX);
        assertThat(ExportFormat.fromParam(" pdf ")).isEqualTo(ExportFormat.PDF);

        assertThatThrownBy(() -> ExportFormat.fromParam("csv"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void xlsxNeutralizesFormulasInTextButKeepsNumericNegativeDifferences() throws IOException {
        ReportFilter filter = new ReportFilter(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 15), List.of(1L));
        CashDifferenceRow rowFormula = new CashDifferenceRow(
                10L, "=CMD()", LocalDate.of(2026, 6, 10),
                "+Cajero", "@Caja1",
                new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("-2000"), "-Sup");
        CashDifferenceRow totals = new CashDifferenceRow(
                null, "TOTAL", null, "Total", null,
                new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("-2000"), null);

        ReportExportDocument doc = ReportExportDocumentMapper.toCashDifferencesDocument(
                new ReportResponse<>(filter, FIXED_NOW, List.of(rowFormula), totals),
                null, null, true, "L01 - Central");

        ExportedReport exported = renderer.render(doc, ExportFormat.XLSX, "Minimarket Test");
        assertThat(exported.fileName()).isEqualTo("reporte-diferencias-caja.xlsx");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(exported.content()))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("'=CMD()");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("'+Cajero");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("'@Caja1");
            assertThat(sheet.getRow(1).getCell(7).getStringCellValue()).isEqualTo("'-Sup");

            Cell diffCell = sheet.getRow(1).getCell(6);
            assertThat(diffCell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(diffCell.getNumericCellValue()).isEqualTo(-2000d);
        }
    }

    @Test
    void pdfRendersGenericTemplateWithEscapedHtmlTildesAndDashboardSections() throws IOException {
        DashboardView dashboard = new DashboardView(
                LocalDate.of(2026, 6, 15),
                new BigDecimal("125000"),
                4L,
                new BigDecimal("31250"),
                new BigDecimal("15.50"),
                List.of(new HourlySalesView(10, new BigDecimal("125000"), 4L)),
                List.of(new TopProductView(1L, "Piña <b>Fina</b> & Ñoquis", new BigDecimal("3.500"),
                        new BigDecimal("45000"))),
                List.of(new PaymentMixView("CASH", new BigDecimal("125000"))),
                new AlertsView(2L, 1L, 0L, 1L, 0L, 0L, 1L));

        ReportExportDocument doc = ReportExportDocumentMapper.toDashboardDocument(dashboard, "L01 - Ñuñoa");
        ExportedReport pdf = renderer.render(doc, ExportFormat.PDF, "Minimarket Don Pepe & Cía.");

        assertThat(pdf.fileName()).isEqualTo("reporte-dashboard.pdf");
        assertThat(new String(pdf.content(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        String text = extractPdfText(pdf.content());
        assertThat(text)
                .contains("Minimarket Don Pepe & Cía.")
                .contains("Dashboard del Día")
                .contains("L01 - Ñuñoa")
                .contains("$125.000")
                .contains("15,50 %")
                .contains("Piña <b>Fina</b> & Ñoquis")
                .contains("Generado: 15-06-2026 12:30")
                .contains("Página 1 de");

        ExportedReport xlsx = renderer.render(doc, ExportFormat.XLSX, "Minimarket Don Pepe & Cía.");
        List<ImportRow> xlsxRows = new CsvXlsxReader().readAll(
                new ByteArrayInputStream(xlsx.content()), xlsx.fileName());
        assertThat(xlsxRows).isNotEmpty();
        assertThat(xlsxRows.getFirst().values())
                .containsEntry("Sección", "Resumen")
                .containsEntry("Concepto", "Ventas netas del día")
                .containsEntry("Monto CLP", "125000");
    }

    @Test
    void pdfRendersEmptyReportMessageGracefully() throws IOException {
        ReportFilter filter = new ReportFilter(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), List.of(1L));
        ReportExportDocument doc = ReportExportDocumentMapper.toSalesByPaymentMethodDocument(
                new ReportResponse<>(filter, FIXED_NOW, List.of(), null), "L01 - Central");

        ExportedReport pdf = renderer.render(doc, ExportFormat.PDF, null);
        assertThat(extractPdfText(pdf.content()))
                .contains("Ventas por Medio de Pago")
                .contains("Sin datos para los filtros seleccionados.");
    }

    private static String extractPdfText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
