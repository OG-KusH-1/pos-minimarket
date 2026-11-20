package cl.minimarket.pos.promotions;

/**
 * Tipo de entidad objetivo de una regla de promoción ({@code MODELO_DATOS.md} B.10).
 */
public enum PromotionTargetType {
    /** Aplica a un producto específico. */
    PRODUCT,
    /** Aplica a todos los productos de una categoría. */
    CATEGORY,
    /** Aplica a todos los productos de una marca. */
    BRAND
}
