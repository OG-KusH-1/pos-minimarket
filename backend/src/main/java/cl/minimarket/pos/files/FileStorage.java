package cl.minimarket.pos.files;

import java.io.InputStream;

/**
 * Puerto de almacenamiento físico de archivos (PLAN A.4, ADR-09).
 *
 * <p>El adaptador MVP guarda los binarios en el sistema de archivos local
 * ({@code LocalFileSystemStorage}); un futuro adaptador S3 puede reemplazarlo sin tocar los
 * llamadores.
 */
public interface FileStorage {

    /**
     * Valida (magic bytes y tamaño según la categoría) y persiste un flujo de bytes bajo una clave
     * UUID aleatoria.
     *
     * @param in flujo de entrada con el contenido del archivo
     * @param contentType tipo MIME declarado por el cliente (puede ser nulo o impreciso)
     * @param size tamaño declarado en bytes (se verifica contra el flujo real)
     * @param category categoría funcional que gobierna tipos y tamaño máximos permitidos
     * @return metadatos técnicos del objeto almacenado
     */
    StoredObject store(InputStream in, String contentType, long size, FileCategory category);

    /**
     * Abre un flujo de lectura para la clave indicada.
     *
     * @param storageKey clave asignada por {@link #store}
     * @return flujo de entrada abierto
     */
    InputStream open(String storageKey);

    /**
     * Elimina el binario asociado a la clave, si existe (operación idempotente).
     *
     * @param storageKey clave asignada por {@link #store}
     */
    void delete(String storageKey);
}
