package cl.minimarket.pos.shared.sequence;

/**
 * Tipos de correlativo de {@code document_sequences.sequence_type} (MODELO_DATOS B.1). Los nombres
 * coinciden exactamente con el {@code CHECK} de la tabla: agregar un tipo exige una migración que
 * amplíe ese {@code CHECK} y, aquí, un valor nuevo.
 *
 * <p>Cada tipo tiene un {@linkplain #code() código corto} (parte del prefijo del folio) y un
 * {@linkplain #scope() alcance}: por local o global (RN-29: los folios tributarios son únicos por
 * tipo en toda la empresa).
 */
public enum SequenceType {

    /** Venta POS. */
    SALE("V", Scope.STORE),
    /** Devolución de venta. */
    SALE_RETURN("D", Scope.STORE),
    /** Orden de compra. */
    PURCHASE_ORDER("OC", Scope.STORE),
    /** Recepción de mercadería. */
    GOODS_RECEIPT("RC", Scope.STORE),
    /** Transferencia entre locales. */
    TRANSFER("TR", Scope.STORE),
    /** Toma de inventario. */
    INVENTORY_COUNT("TI", Scope.STORE),
    /** Ajuste de stock. */
    STOCK_ADJUSTMENT("AJ", Scope.STORE),
    /** Merma. */
    SHRINKAGE("MR", Scope.STORE),
    /** Turno de caja. */
    CASH_SESSION("TU", Scope.STORE),
    /** Folio de boleta (documento tributario simulado), global. */
    FOLIO_BOLETA("BOL", Scope.GLOBAL),
    /** Folio de nota de crédito (documento tributario simulado), global. */
    FOLIO_CREDIT_NOTE("NC", Scope.GLOBAL);

    /** Si el correlativo es independiente por local o único en toda la empresa. */
    public enum Scope {
        /** Una secuencia por local: {@code storeId} es obligatorio. */
        STORE,
        /** Una única secuencia: {@code storeId} debe ser {@code null}. */
        GLOBAL
    }

    private final String code;
    private final Scope scope;

    SequenceType(String code, Scope scope) {
        this.code = code;
        this.scope = scope;
    }

    /** @return código corto usado en el prefijo del folio (p. ej. {@code V} para ventas) */
    public String code() {
        return code;
    }

    /** @return alcance del correlativo */
    public Scope scope() {
        return scope;
    }
}
