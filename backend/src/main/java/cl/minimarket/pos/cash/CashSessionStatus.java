package cl.minimarket.pos.cash;

/**
 * Estados posibles de un turno de caja ({@code cash_sessions.status}, {@code MODELO_DATOS.md} B.8).
 */
public enum CashSessionStatus {
    /** Turno abierto y operativo en la terminal. */
    OPEN,
    /** Turno cerrado con arqueo registrado. */
    CLOSED
}
