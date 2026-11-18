package cl.minimarket.pos.inventory.application.count;

/**
 * Estado del ciclo de vida de una toma física de inventario ({@code MODELO_DATOS} B.14).
 */
public enum CountStatus {
    /** En ejecución: permite registrar o actualizar conteos por línea. */
    OPEN,
    /** Enviada a revisión: todas las líneas fueron contadas y espera aprobación o rechazo. */
    SUBMITTED,
    /** Aprobada: los ajustes por diferencia ya fueron aplicados vía {@code StockApi}. */
    APPROVED,
    /** Rechazada (valor histórico admitido por el {@code CHECK}; {@code reject} vuelve a {@code OPEN}). */
    REJECTED,
    /** Cancelada antes de aprobarse: no aplica movimientos y libera el alcance abierto. */
    CANCELLED
}
