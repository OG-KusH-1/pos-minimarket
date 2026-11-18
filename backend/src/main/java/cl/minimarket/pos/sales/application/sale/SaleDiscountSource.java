package cl.minimarket.pos.sales.application.sale;

/**
 * Origen de un descuento aplicado en una venta ({@code sale_discounts.source},
 * {@code MODELO_DATOS.md} B.9).
 */
public enum SaleDiscountSource {
    /** Descuento manual digitado en el POS. */
    MANUAL,
    /** Descuento aplicado automáticamente por una promoción vigente. */
    PROMOTION
}
