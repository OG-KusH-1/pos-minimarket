package cl.minimarket.pos.inventory;

/**
 * Tipos de movimiento de stock registrados en el kardex ({@code stock_movements.movement_type}).
 *
 * <p>Corresponde al enum {@code movement_type} de {@code MODELO_DATOS.md} (sección B.14) y al
 * constraint {@code ck_stock_movements_type} de la base de datos.
 */
public enum StockMovementType {

    /** Carga inicial de inventario (entrada). */
    INITIAL(true),
    /** Recepción de orden de compra (entrada). */
    PURCHASE_RECEIPT(true),
    /** Venta POS (salida). */
    SALE(false),
    /** Anulación de venta POS (entrada). */
    SALE_VOID(true),
    /** Devolución de venta POS con reingreso a stock (entrada). */
    SALE_RETURN(true),
    /** Merma aprobada o sistémica (salida). */
    SHRINKAGE(false),
    /** Ajuste de inventario positivo o sobrante en toma (entrada). */
    ADJUSTMENT_IN(true),
    /** Ajuste de inventario negativo o faltante en toma (salida). */
    ADJUSTMENT_OUT(false),
    /** Despacho de transferencia entre locales (salida en origen). */
    TRANSFER_OUT(false),
    /** Recepción de transferencia entre locales (entrada en destino). */
    TRANSFER_IN(true);

    private final boolean inbound;

    StockMovementType(boolean inbound) {
        this.inbound = inbound;
    }

    /**
     * Indica si el movimiento aumenta el stock ({@code true}) o lo disminuye ({@code false}).
     *
     * @return {@code true} para entradas; {@code false} para salidas
     */
    public boolean isInbound() {
        return inbound;
    }

    /**
     * Indica si el movimiento disminuye el stock.
     *
     * @return {@code true} para salidas; {@code false} para entradas
     */
    public boolean isOutbound() {
        return !inbound;
    }
}
