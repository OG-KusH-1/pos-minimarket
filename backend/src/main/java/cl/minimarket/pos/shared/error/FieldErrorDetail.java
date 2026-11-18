package cl.minimarket.pos.shared.error;

/**
 * Elemento del arreglo {@code errors[]} de la respuesta de error (A.5): un problema asociado a
 * un campo, parámetro o regla puntual.
 *
 * @param field campo, parámetro o ruta afectada (p. ej. {@code "items[0].quantity"}, {@code "sort"})
 * @param code código estable del problema (p. ej. {@code NOT_BLANK}, {@code SORT_FIELD_NOT_ALLOWED})
 * @param message mensaje legible para el usuario, sin datos internos
 */
public record FieldErrorDetail(String field, String code, String message) {
}
