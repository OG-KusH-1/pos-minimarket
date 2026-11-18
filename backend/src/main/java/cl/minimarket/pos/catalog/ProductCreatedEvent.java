package cl.minimarket.pos.catalog;

/**
 * Evento de dominio publicado al crear un producto en el catálogo (PLAN A.4, RN-04).
 *
 * <p>El módulo {@code inventory} lo escucha para crear las filas de stock en cero en cada local
 * activo cuando {@code saleType != SaleType.PACK}.
 *
 * @param productId identificador del producto recién creado
 * @param sku       SKU único del producto
 * @param saleType  modalidad de venta del producto
 */
public record ProductCreatedEvent(
        Long productId,
        String sku,
        SaleType saleType
) {
}
