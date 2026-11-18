package cl.minimarket.pos.shrinkage;

/**
 * Estados del ciclo de vida de una merma ({@code MODELO_DATOS.md} B.6 y B.14).
 */
public enum ShrinkageStatus {
    /** Pendiente de aprobación por un supervisor. */
    PENDING_APPROVAL,
    /** Aprobada (manual o automáticamente). */
    APPROVED,
    /** Rechazada (anotación sin efecto en stock ni reportes). */
    REJECTED
}
