package cl.minimarket.pos.shared.export;

/**
 * El archivo de importación no es un CSV/XLSX válido o excede los límites configurados.
 *
 * <p>Es una excepción propia de {@code shared/export} para no depender del catálogo de errores de
 * otros paquetes; el módulo que importa (p. ej. {@code dataexchange}) la traduce a su código de
 * error de negocio (p. ej. {@code FILE_INVALID}).
 */
public class InvalidImportFileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidImportFileException(String message) {
        super(message);
    }

    public InvalidImportFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
