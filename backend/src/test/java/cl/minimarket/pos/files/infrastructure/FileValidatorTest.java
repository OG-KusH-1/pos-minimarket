package cl.minimarket.pos.files.infrastructure;

import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de {@link FileValidator} (magic bytes y límites de tamaño).
 */
class FileValidatorTest {

    @Test
    void acceptsValidPngForProductImage() {
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        String mime = FileValidator.detectAndValidateContentType(
                pngHeader, pngHeader.length, "application/octet-stream", FileCategory.PRODUCT_IMAGE);
        assertThat(mime).isEqualTo("image/png");
    }

    @Test
    void rejectsExecutableMasqueradingAsPng() {
        byte[] exeHeader = new byte[]{'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};
        assertThatThrownBy(() -> FileValidator.detectAndValidateContentType(
                exeHeader, exeHeader.length, "image/png", FileCategory.PRODUCT_IMAGE))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FILE_INVALID);
    }

    @Test
    void rejectsImageOverFiveMegabytes() {
        assertThatThrownBy(() -> FileValidator.validateDeclaredSize(
                FileValidator.MAX_IMAGE_BYTES + 1, FileCategory.COMPANY_LOGO))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FILE_INVALID);
    }

    @Test
    void acceptsCsvForImportUpToTwentyMegabytes() {
        byte[] csv = "sku,name,priceGross\n1001,Arroz,1500\n".getBytes(StandardCharsets.UTF_8);
        FileValidator.validateDeclaredSize(FileValidator.MAX_DOCUMENT_BYTES, FileCategory.IMPORT);
        String mime = FileValidator.detectAndValidateContentType(
                csv, csv.length, "text/csv", FileCategory.IMPORT);
        assertThat(mime).isEqualTo("text/csv");
    }
}
