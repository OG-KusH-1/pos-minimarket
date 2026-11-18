package cl.minimarket.pos.files;

import java.time.Instant;

/**
 * Metadatos públicos de un archivo registrado en {@code stored_files}.
 *
 * @param id identificador del registro en {@code stored_files}
 * @param storageKey clave opaca en {@link FileStorage}
 * @param originalName nombre original del archivo
 * @param contentType tipo MIME real verificado
 * @param sizeBytes tamaño en bytes
 * @param sha256 huella SHA-256 hexadecimal
 * @param category categoría funcional
 * @param createdAt instante de creación (UTC)
 */
public record FileMetadata(
        Long id,
        String storageKey,
        String originalName,
        String contentType,
        long sizeBytes,
        String sha256,
        FileCategory category,
        Instant createdAt
) {
}
