package cl.minimarket.pos.inventory.application.adjustment;

/**
 * Estados del ciclo de vida de un ajuste manual de stock ({@code MODELO_DATOS.md} B.5 y B.14).
 *
 * <p>Los nombres coinciden exactamente con el {@code CHECK} de {@code stock_adjustments.status}.
 */
public enum AdjustmentStatus {
    /** Pendiente de aprobación por un supervisor; aún no modifica el stock. */
    PENDING_APPROVAL,
    /** Aprobado; generó movimientos en {@code stock_movements} y actualizó {@code stock}. */
    APPROVED,
    /** Rechazado; solo queda registro histórico sin efecto en el stock. */
    REJECTED
}
