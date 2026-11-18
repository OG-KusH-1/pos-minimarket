package cl.minimarket.pos.dataexchange.domain;

/**
 * Estados del ciclo de vida de un trabajo de importación ({@code import_jobs.status}).
 */
public enum ImportJobStatus {
    PENDING,
    VALIDATED,
    PROCESSING,
    COMPLETED,
    FAILED
}
