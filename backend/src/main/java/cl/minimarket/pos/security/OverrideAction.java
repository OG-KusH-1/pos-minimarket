package cl.minimarket.pos.security;

/**
 * Acciones de negocio que admiten autorización de supervisor (override) mediante token de un solo uso
 * ({@code ROLES_PERMISOS.md} D.4, {@code MODELO_DATOS.md} B.2 {@code supervisor_authorizations}).
 */
public enum OverrideAction {
    /** Descuento por ítem sobre el límite del rol. */
    DISCOUNT_ITEM_OVER_LIMIT,
    /** Descuento al total de la venta sobre el límite del rol. */
    DISCOUNT_TOTAL_OVER_LIMIT,
    /** Anulación de una venta. */
    SALE_VOID,
    /** Devolución de una venta. */
    SALE_RETURN,
    /** Retiro de efectivo de caja sobre el límite del rol. */
    CASH_OUT_OVER_LIMIT,
    /** Venta a crédito por sobre el cupo del cliente. */
    CREDIT_OVER_LIMIT,
    /** Eliminación de una venta suspendida de otro cajero. */
    SUSPENDED_SALE_DELETE,
    /** Reimpresión de comprobante de venta. */
    RECEIPT_REPRINT,
    /** Aprobación de merma. */
    SHRINKAGE_APPROVAL,
    /** Aprobación de ajuste de stock. */
    STOCK_ADJUSTMENT_APPROVAL,
    /** Aprobación de diferencia de cierre de caja. */
    CASH_DIFFERENCE_APPROVAL
}
