package cl.minimarket.pos.reporting.export.domain;

import java.util.Objects;

/**
 * Archivo binario exportado junto con los metadatos necesarios para construir la respuesta HTTP
 * con {@code Content-Disposition: attachment}.
 *
 * @param content     contenido binario del archivo XLSX o PDF
 * @param fileName    nombre sugerido de descarga con extensión
 * @param contentType tipo MIME del archivo
 */
public record ExportedReport(byte[] content, String fileName, String contentType) {

    public ExportedReport {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
    }
}
