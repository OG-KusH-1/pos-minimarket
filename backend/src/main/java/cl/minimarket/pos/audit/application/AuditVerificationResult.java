package cl.minimarket.pos.audit.application;

/**
 * Resultado de la verificación de integridad de la cadena de hash de {@code audit_log}.
 *
 * @param checked cantidad de registros verificados antes de completar o detectar una ruptura
 * @param valid {@code true} si todos los registros verificados mantienen la cadena intacta
 * @param firstBrokenId id del primer registro cuyo {@code prev_hash} o {@code hash} no coincide,
 *        o {@code null} cuando {@code valid} es {@code true}
 */
public record AuditVerificationResult(
        long checked,
        boolean valid,
        Long firstBrokenId
) {
}
