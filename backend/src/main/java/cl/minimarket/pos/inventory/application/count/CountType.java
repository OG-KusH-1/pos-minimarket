package cl.minimarket.pos.inventory.application.count;

/**
 * Tipo de toma física de inventario ({@code MODELO_DATOS} B.14).
 */
public enum CountType {
    /** Inventario general: incluye todos los productos con stock positivo en el local. */
    FULL,
    /** Inventario cíclico: acotado a una categoría (incluyendo sus subcategorías). */
    CYCLIC
}
