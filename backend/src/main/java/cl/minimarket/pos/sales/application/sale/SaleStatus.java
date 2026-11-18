package cl.minimarket.pos.sales.application.sale;

/**
 * Estado del ciclo de vida de una venta ({@code sales.status}, {@code MODELO_DATOS.md} B.9).
 */
public enum SaleStatus {
    /** Venta emitida y vigente. */
    COMPLETED,
    /** Venta anulada en su totalidad en el mismo turno (T71). */
    VOIDED,
    /** Venta con devolución parcial acumulada (T71). */
    PARTIALLY_RETURNED,
    /** Venta devuelta en su totalidad (T71). */
    RETURNED
}
