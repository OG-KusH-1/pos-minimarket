package cl.minimarket.pos.files.web;

import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileMetadata;

/**
 * DTO de respuesta al registrar un archivo ({@code API.md} F.3 {@code FileResponse}).
 */
public record FileResponse(
        Long id,
        String originalName,
        String contentType,
        long sizeBytes,
        FileCategory category,
        String url
) {

    /** Construye el DTO a partir de los metadatos registrados. */
    public static FileResponse from(FileMetadata meta) {
        return new FileResponse(
                meta.id(),
                meta.originalName(),
                meta.contentType(),
                meta.sizeBytes(),
                meta.category(),
                "/api/v1/files/" + meta.id() + "/content"
        );
    }
}
