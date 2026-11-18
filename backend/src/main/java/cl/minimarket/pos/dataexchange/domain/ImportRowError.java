package cl.minimarket.pos.dataexchange.domain;

/**
 * Error de validación detectado en una fila específica de un archivo de importación.
 *
 * @param row     número de fila en el archivo (encabezado = 1)
 * @param field   nombre de la columna o campo con error
 * @param message descripción legible del error
 */
public record ImportRowError(
        int row,
        String field,
        String message
) {
}
