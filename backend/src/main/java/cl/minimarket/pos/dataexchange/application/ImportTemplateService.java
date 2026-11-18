package cl.minimarket.pos.dataexchange.application;

import cl.minimarket.pos.dataexchange.domain.ImportType;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.export.CsvWriter;
import cl.minimarket.pos.shared.export.XlsxWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Genera plantillas descargables (CSV o XLSX) con los encabezados oficiales de importación
 * ({@code API.md} F.3).
 */
@Service
public class ImportTemplateService {

    /** Columnas oficiales de la plantilla {@link ImportType#PRODUCTS}. */
    public static final List<String> PRODUCTS_HEADERS = List.of(
            "sku", "name", "category", "subcategory", "brand",
            "saleType", "priceGross", "barcodes", "taxes", "restricted", "lotControlled"
    );

    /** Columnas oficiales de la plantilla {@link ImportType#PRODUCT_SUPPLIERS}. */
    public static final List<String> PRODUCT_SUPPLIERS_HEADERS = List.of(
            "sku", "supplierRut", "purchaseUnitName", "units", "cost", "moq"
    );

    /** Columnas oficiales de la plantilla {@link ImportType#INITIAL_STOCK}. */
    public static final List<String> INITIAL_STOCK_HEADERS = List.of(
            "sku", "quantity", "unitCost", "lotNumber", "expiryDate"
    );

    /** Resultado binario de una plantilla generada. */
    public record GeneratedTemplate(byte[] content, String fileName, String contentType) {
    }

    /**
     * Genera la plantilla vacía con encabezados para el tipo y formato solicitados.
     *
     * @param importType tipo de importación
     * @param format     {@code csv} o {@code xlsx}
     * @return contenido binario y metadatos de descarga
     */
    public GeneratedTemplate generate(ImportType importType, String format) {
        String normalizedFormat = normalizeFormat(format);
        List<String> headers = headersFor(importType);
        String baseName = "plantilla-" + importType.name().toLowerCase(Locale.ROOT);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if ("csv".equals(normalizedFormat)) {
            try (CsvWriter csv = new CsvWriter(out, headers)) {
                // Solo encabezados
            }
            return new GeneratedTemplate(
                    out.toByteArray(),
                    baseName + ".csv",
                    "text/csv; charset=utf-8"
            );
        }

        List<XlsxWriter.Column> columns = headers.stream()
                .map(XlsxWriter.Column::text)
                .toList();
        try (XlsxWriter xlsx = new XlsxWriter(out, importType.name(), columns)) {
            xlsx.finish();
        }
        return new GeneratedTemplate(
                out.toByteArray(),
                baseName + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );
    }

    /**
     * Devuelve los encabezados requeridos/esperados para un tipo de importación.
     */
    public static List<String> headersFor(ImportType importType) {
        return switch (importType) {
            case PRODUCTS -> PRODUCTS_HEADERS;
            case PRODUCT_SUPPLIERS -> PRODUCT_SUPPLIERS_HEADERS;
            case INITIAL_STOCK -> INITIAL_STOCK_HEADERS;
        };
    }

    /**
     * Normaliza y valida el formato de archivo ({@code csv} o {@code xlsx}).
     */
    public static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "xlsx";
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(normalized) && !"xlsx".equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Formato no soportado: '" + format + "'. Use 'csv' o 'xlsx'.");
        }
        return normalized;
    }
}
