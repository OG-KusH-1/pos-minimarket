package cl.minimarket.pos.audit;

/**
 * Puerto público del módulo de auditoría para registrar eventos en la bitácora inmutable.
 *
 * <p>Toda invocación a {@link #record(AuditEvent)} se ejecuta dentro de la transacción activa del
 * llamador ({@code Propagation.MANDATORY}): si la operación de negocio hace rollback, su registro
 * de auditoría también se revierte y no quedan huérfanos en la cadena de hash (ADR-18, RN-25).
 */
public interface AuditService {

    /**
     * Registra un evento en {@code audit_log} encadenando su hash SHA-256 al último registro.
     *
     * <p><strong>Recomendación de uso:</strong> invocar este método hacia el final de la
     * transacción de negocio, una vez ejecutadas las validaciones y escrituras principales. La
     * serialización de la cadena utiliza {@code pg_advisory_xact_lock}, bloqueo que permanece
     * tomado hasta el commit o rollback de la transacción; llamarlo al inicio retendría el lock
     * innecesariamente y aumentaría la contención bajo concurrencia.
     *
     * @param event datos del evento de negocio a auditar; nunca nulo
     */
    void record(AuditEvent event);
}
