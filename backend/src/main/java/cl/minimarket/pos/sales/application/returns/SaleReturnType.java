package cl.minimarket.pos.sales.application.returns;

/**
 * Tipo de documento de reversa de venta ({@code sale_returns.return_type}, {@code MODELO_DATOS.md} B.9).
 */
public enum SaleReturnType {
    /** Anulación total de una venta en el mismo turno abierto (RN-12). */
    VOID,
    /** Devolución parcial o total dentro de la ventana configurada (RN-12, RN-13, RN-14). */
    RETURN
}
