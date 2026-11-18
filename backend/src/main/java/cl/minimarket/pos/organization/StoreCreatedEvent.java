package cl.minimarket.pos.organization;

/**
 * Evento de dominio publicado al crear un local nuevo (PLAN A.4, RN-04).
 *
 * <p>El módulo {@code inventory} lo escucha para crear las filas de stock en cero de los productos
 * existentes.
 */
public record StoreCreatedEvent(
        Long storeId,
        String code,
        String name
) {
}
