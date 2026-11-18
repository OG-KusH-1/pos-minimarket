package cl.minimarket.pos.files.infrastructure;

import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;

import java.util.Locale;
import java.util.Set;

/**
 * Valida tamaño máximo y tipo real por <em>magic bytes</em> según la {@link FileCategory}
 * (MODELO_DATOS B.1, HU-CAT-13).
 *
 * <p>Nunca confía en la extensión ni en el {@code Content-Type} declarado por el cliente: un
 * ejecutable renombrado a {@code .png} es rechazado con 422 {@code FILE_INVALID}.
 */
public final class FileValidator {

    /** Tamaño máximo para imágenes y evidencias: 5 MB. */
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /** Tamaño máximo para archivos de importación y exportación: 20 MB. */
    public static final long MAX_DOCUMENT_BYTES = 20L * 1024 * 1024;

    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_PNG = "image/png";
    private static final String MIME_WEBP = "image/webp";
    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MIME_CSV = "text/csv";

    private static final Set<String> CSV_DECLARED_MIMES = Set.of(
            "text/csv", "application/csv", "text/plain", "application/vnd.ms-excel"
    );

    private FileValidator() {
    }

    /**
     * Devuelve el tamaño máximo en bytes permitido para una categoría.
     */
    public static long maxSizeFor(FileCategory category) {
        return switch (category) {
            case PRODUCT_IMAGE, COMPANY_LOGO, SHRINKAGE_EVIDENCE -> MAX_IMAGE_BYTES;
            case IMPORT, EXPORT -> MAX_DOCUMENT_BYTES;
        };
    }

    /**
     * Valida que el tamaño declarado sea positivo y no exceda el máximo de la categoría.
     */
    public static void validateDeclaredSize(long size, FileCategory category) {
        if (size == 0) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "El archivo está vacío.");
        }
        long max = maxSizeFor(category);
        if (size > max) {
            throw new BusinessException(ErrorCode.FILE_INVALID,
                    "El archivo excede el tamaño máximo permitido (" + (max / (1024 * 1024)) + " MB).");
        }
    }

    /**
     * Detecta el tipo MIME real a partir de los primeros bytes del archivo y verifica que esté
     * permitido para la categoría indicada.
     *
     * @param header primeros bytes leídos del flujo (al menos 12 bytes si el archivo es mayor)
     * @param headerLen cantidad de bytes válidos en {@code header}
     * @param declaredContentType tipo MIME informado por el cliente (solo usado como pista para CSV)
     * @param category categoría funcional
     * @return tipo MIME real verificado
     * @throws BusinessException 422 {@code FILE_INVALID} si los magic bytes no coinciden con ningún
     *         formato permitido para la categoría
     */
    public static String detectAndValidateContentType(byte[] header, int headerLen,
                                                      String declaredContentType, FileCategory category) {
        if (headerLen <= 0) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "El archivo está vacío.");
        }

        String detected = detectBinaryType(header, headerLen);
        if (detected == null && isPlainTextCsvCandidate(header, headerLen, declaredContentType)) {
            detected = MIME_CSV;
        }

        if (detected == null || !isAllowedForCategory(detected, category)) {
            throw new BusinessException(ErrorCode.FILE_INVALID,
                    "El formato del archivo no es válido para la categoría " + category + ".");
        }
        return detected;
    }

    private static boolean isAllowedForCategory(String mime, FileCategory category) {
        return switch (category) {
            case PRODUCT_IMAGE, COMPANY_LOGO ->
                    mime.equals(MIME_JPEG) || mime.equals(MIME_PNG) || mime.equals(MIME_WEBP);
            case SHRINKAGE_EVIDENCE ->
                    mime.equals(MIME_JPEG) || mime.equals(MIME_PNG) || mime.equals(MIME_WEBP) || mime.equals(MIME_PDF);
            case IMPORT ->
                    mime.equals(MIME_CSV) || mime.equals(MIME_XLSX);
            case EXPORT ->
                    mime.equals(MIME_PDF) || mime.equals(MIME_CSV) || mime.equals(MIME_XLSX);
        };
    }

    private static String detectBinaryType(byte[] b, int len) {
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (len >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return MIME_PNG;
        }
        // JPEG: FF D8 FF
        if (len >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return MIME_JPEG;
        }
        // WEBP: "RIFF" .... "WEBP"
        if (len >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return MIME_WEBP;
        }
        // PDF: "%PDF-"
        if (len >= 5
                && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return MIME_PDF;
        }
        // XLSX (ZIP container): PK\x03\x04
        if (len >= 4
                && b[0] == 0x50 && b[1] == 0x4B && b[2] == 0x03 && b[3] == 0x04) {
            return MIME_XLSX;
        }
        return null;
    }

    private static boolean isPlainTextCsvCandidate(byte[] b, int len, String declaredContentType) {
        // Rechaza firmas conocidas de ejecutables (MZ = PE/EXE, \x7FELF = ELF) o bytes de control binarios
        if (len >= 2 && b[0] == 'M' && b[1] == 'Z') {
            return false;
        }
        if (len >= 4 && b[0] == 0x7F && b[1] == 'E' && b[2] == 'L' && b[3] == 'F') {
            return false;
        }
        for (int i = 0; i < len; i++) {
            int unsigned = b[i] & 0xFF;
            if (unsigned == 0x00 || (unsigned < 0x20 && unsigned != '\t' && unsigned != '\n' && unsigned != '\r')) {
                return false;
            }
        }
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return true;
        }
        String normalized = declaredContentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return CSV_DECLARED_MIMES.contains(normalized);
    }
}
