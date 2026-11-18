package cl.minimarket.pos.files;

import java.io.InputStream;

/**
 * Flujo de lectura de un archivo junto con sus metadatos.
 *
 * <p>El llamador es responsable de cerrar {@link #stream()} tras consumirlo.
 *
 * @param metadata metadatos del archivo
 * @param stream flujo de bytes del contenido almacenado
 */
public record FileContent(
        FileMetadata metadata,
        InputStream stream
) {
}
