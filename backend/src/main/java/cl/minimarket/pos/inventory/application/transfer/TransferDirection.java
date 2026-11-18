package cl.minimarket.pos.inventory.application.transfer;

/** Dirección de una transferencia respecto al local activo en la consulta. */
public enum TransferDirection {
    /** Transferencias cuyo destino es el local activo (entrantes). */
    IN,
    /** Transferencias cuyo origen es el local activo (salientes). */
    OUT
}
