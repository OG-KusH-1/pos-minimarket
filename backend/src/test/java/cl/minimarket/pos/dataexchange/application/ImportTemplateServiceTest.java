package cl.minimarket.pos.dataexchange.application;

import cl.minimarket.pos.dataexchange.application.ImportTemplateService.GeneratedTemplate;
import cl.minimarket.pos.dataexchange.domain.ImportType;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.export.CsvXlsxReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de generación de plantillas CSV y XLSX ({@link ImportTemplateService}).
 */
class ImportTemplateServiceTest {

    private final ImportTemplateService service = new ImportTemplateService();
    private final CsvXlsxReader reader = new CsvXlsxReader();

    @Test
    void generatesCsvTemplateWithExpectedHeaders() {
        GeneratedTemplate template = service.generate(ImportType.PRODUCTS, "csv");

        assertThat(template.fileName()).isEqualTo("plantilla-products.csv");
        assertThat(template.contentType()).startsWith("text/csv");
        String text = new String(template.content(), StandardCharsets.UTF_8);
        assertThat(text).contains("sku;name;category;subcategory;brand;saleType;priceGross;barcodes;taxes;restricted;lotControlled");
        assertThat(reader.readAll(new ByteArrayInputStream(template.content()), template.fileName())).isEmpty();
    }

    @Test
    void generatesXlsxTemplateForAllImportTypes() {
        for (ImportType type : ImportType.values()) {
            GeneratedTemplate template = service.generate(type, "xlsx");
            assertThat(template.fileName()).endsWith(".xlsx");
            assertThat(template.contentType())
                    .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            assertThat(template.content()).hasSizeGreaterThan(100);
            assertThat(reader.readAll(new ByteArrayInputStream(template.content()), template.fileName())).isEmpty();
        }
    }

    @Test
    void rejectsUnsupportedFormat() {
        assertThatThrownBy(() -> service.generate(ImportType.PRODUCTS, "pdf"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
