package cl.minimarket.pos.inventory;

/**
 * Origen documental de un movimiento de stock ({@code stock_movements.source_type}).
 *
 * <p>Corresponde al enum {@code movement_source_type} de {@code MODELO_DATOS.md} (sección B.14) y
 * al constraint {@code ck_stock_movements_source_type} de la base de datos.
 */
public enum StockSourceType {

    /** Carga inicial de catálogo/inventario. */
    INITIAL_LOAD,
    /** Recepción de mercadería de proveedor. */
    GOODS_RECEIPT,
    /** Venta POS (y su anulación). */
    SALE,
    /** Devolución de venta POS. */
    SALE_RETURN,
    /** Registro de merma. */
    SHRINKAGE,
    /** Ajuste manual de stock. */
    STOCK_ADJUSTMENT,
    /** Toma de inventario físico. */
    INVENTORY_COUNT,
    /** Transferencia entre locales. */
    STOCK_TRANSFER
}
