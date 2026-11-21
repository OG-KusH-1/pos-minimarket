package cl.minimarket.pos.reporting.export.application;

import cl.minimarket.pos.reporting.export.domain.ExportFormat;
import cl.minimarket.pos.reporting.export.domain.ExportedReport;
import cl.minimarket.pos.reporting.export.domain.ReportExportDocument;
import cl.minimarket.pos.shared.export.PdfRenderer;
import cl.minimarket.pos.shared.export.XlsxWriter;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renderizador genérico de reportes hacia XLSX (reutilizando {@link XlsxWriter}) y PDF
 * (reutilizando {@link PdfRenderer} con {@code templates/pdf/report.html}).
 */
@Component
public class ReportExportRenderer {

    private static final String REPORT_TEMPLATE = "report";
    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private final PdfRenderer pdfRenderer;
    private final BusinessClock clock;

    public ReportExportRenderer(PdfRenderer pdfRenderer, BusinessClock clock) {
        this.pdfRenderer = Objects.requireNonNull(pdfRenderer, "pdfRenderer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Genera el archivo binario en el formato solicitado para el documento ya construido.
     *
     * @param document    modelo genérico del reporte
     * @param format      formato de salida ({@link ExportFormat#XLSX} o {@link ExportFormat#PDF})
     * @param companyName nombre de empresa para el encabezado del PDF
     * @return archivo binario listo para descarga HTTP
     */
    public ExportedReport render(ReportExportDocument document, ExportFormat format, String companyName) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(format, "format");
        byte[] content = switch (format) {
            case XLSX -> renderXlsx(document);
            case PDF -> renderPdf(document, companyName);
        };
        String fileName = document.type().fileBaseName() + "." + format.extension();
        return new ExportedReport(content, fileName, format.contentType());
    }

    private static byte[] renderXlsx(ReportExportDocument document) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter xlsx = new XlsxWriter(out, document.type().sheetName(), document.xlsxColumns())) {
            for (List<Object> row : document.xlsxRows()) {
                xlsx.writeRow(row);
            }
            xlsx.finish();
        }
        return out.toByteArray();
    }

    private byte[] renderPdf(ReportExportDocument document, String companyName) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("title", document.title());
        model.put("company", (companyName == null || companyName.isBlank()) ? "Minimarket" : companyName);
        model.put("subtitle", document.subtitle());
        model.put("filters", document.filters());
        model.put("kpis", document.kpis());
        model.put("sections", document.sections());
        model.put("generatedAt", GENERATED_AT.format(LocalDateTime.ofInstant(clock.now(), clock.zone())));
        return pdfRenderer.render(REPORT_TEMPLATE, model);
    }
}
