package cl.minimarket.pos.inventory.application.transfer;

/** Estado del ciclo de vida de una transferencia de stock entre locales. */
public enum TransferStatus {
    /** Borrador editable o cancelable en el local de origen antes del despacho. */
    DRAFT,
    /** Despachada desde origen; stock descontado en origen y en tránsito hacia destino. */
    SENT,
    /** Recepcionada en el local de destino (total o parcialmente con diferencias). */
    RECEIVED,
    /** Cancelada desde borrador sin afectar stock. */
    CANCELLED
}
