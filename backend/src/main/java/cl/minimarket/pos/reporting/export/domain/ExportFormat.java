package cl.minimarket.pos.reporting.export.domain;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Formatos binarios soportados por los endpoints de exportación de reportes ({@code API.md} F.1/F.2).
 */
public enum ExportFormat {

    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", MediaType.APPLICATION_PDF_VALUE);

    private final String extension;
    private final String contentType;

    ExportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /** Extensión de archivo en minúsculas ({@code xlsx} o {@code pdf}). */
    public String extension() {
        return extension;
    }

    /** Tipo MIME HTTP de la respuesta. */
    public String contentType() {
        return contentType;
    }

    /**
     * Normaliza y valida el parámetro HTTP {@code format}. Si viene nulo o en blanco usa
     * {@link #XLSX} por defecto; cualquier otro valor lanza 400 {@code VALIDATION_ERROR}.
     *
     * @param raw valor recibido en query param
     * @return formato validado
     */
    public static ExportFormat fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return XLSX;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ExportFormat value : values()) {
            if (value.extension.equals(normalized)) {
                return value;
            }
        }
        throw new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "Formato de exportación no soportado: '" + raw + "'. Use 'xlsx' o 'pdf'.",
                Map.of(),
                List.of(new FieldErrorDetail("format", "INVALID_VALUE", "Valores permitidos: xlsx, pdf.")));
    }
}
