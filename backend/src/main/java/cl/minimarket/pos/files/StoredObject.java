package cl.minimarket.pos.files;

/**
 * Resultado de almacenar un binario en {@link FileStorage}.
 *
 * @param storageKey clave opaca única (UUID) asignada al objeto
 * @param contentType tipo MIME real detectado por contenido (magic bytes)
 * @param sizeBytes tamaño real en bytes leídos del flujo
 * @param sha256 huella SHA-256 en hexadecimal minúscula (64 caracteres)
 */
public record StoredObject(
        String storageKey,
        String contentType,
        long sizeBytes,
        String sha256
) {
}
