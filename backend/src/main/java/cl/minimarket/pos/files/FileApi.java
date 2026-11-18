package cl.minimarket.pos.files;

import java.io.InputStream;

/**
 * API pública del módulo {@code files} (PLAN A.4).
 *
 * <p>Coordina la validación y almacenamiento físico vía {@link FileStorage} con el registro de
 * metadatos en {@code stored_files}.
 */
public interface FileApi {

    /**
     * Valida, almacena el binario y registra la metadata en {@code stored_files}.
     *
     * @param in contenido del archivo
     * @param originalName nombre original del archivo
     * @param contentType tipo MIME declarado
     * @param size tamaño declarado en bytes
     * @param category categoría funcional
     * @return metadatos del archivo registrado
     */
    FileMetadata register(InputStream in, String originalName, String contentType, long size, FileCategory category);

    /**
     * Obtiene los metadatos de un archivo activo (no eliminado).
     *
     * @param fileId identificador en {@code stored_files}
     * @return metadatos del archivo
     */
    FileMetadata get(Long fileId);

    /**
     * Abre el contenido binario de un archivo activo junto con sus metadatos.
     *
     * @param fileId identificador en {@code stored_files}
     * @return metadatos y flujo de lectura
     */
    FileContent open(Long fileId);
}
