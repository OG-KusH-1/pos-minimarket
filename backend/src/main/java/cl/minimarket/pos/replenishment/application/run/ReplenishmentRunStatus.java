package cl.minimarket.pos.replenishment.application.run;

/**
 * Estados de una corrida de reabastecimiento ({@code ck_replenishment_runs_status}, B.11).
 *
 * <p>En el MVP síncrono la corrida se crea {@link #RUNNING}, calcula y termina {@link #COMPLETED}
 * en la misma transacción. {@link #FAILED} queda reservado: si el cálculo lanza, la transacción
 * hace rollback y la corrida no se persiste (decisión documentada en la entrega de T80).
 */
public enum ReplenishmentRunStatus {
    /** En ejecución: fila creada, cálculo en curso (transitorio dentro de la transacción). */
    RUNNING,
    /** Corrida completada con sus líneas persistidas. */
    COMPLETED,
    /** Corrida fallida (reservado para una ejecución asíncrona futura). */
    FAILED
}
