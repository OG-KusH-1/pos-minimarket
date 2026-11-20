package cl.minimarket.pos.sales.application.returns;

/**
 * Motivos estandarizados de anulación o devolución de venta ({@code ck_sale_returns_reason_code}).
 */
public enum SaleReturnReasonCode {
    CUSTOMER_REQUEST,
    DEFECTIVE,
    EXPIRED,
    WRONG_ITEM,
    CASHIER_ERROR,
    PRICE_ERROR,
    OTHER
}
