package cl.minimarket.pos.catalog;

/**
 * Tipo de cambio registrado en el historial de precios y costos ({@code price_history}, B.14).
 */
public enum PriceChangeType {
    SALE_PRICE,
    COST_MANUAL,
    COST_RECEIPT,
    COST_INITIAL
}
