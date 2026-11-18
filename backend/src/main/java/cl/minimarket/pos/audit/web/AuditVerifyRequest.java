package cl.minimarket.pos.audit.web;

/**
 * Solicitud para verificar la integridad de la cadena de hash de {@code audit_log}
 * (F.3 {@code AuditVerifyRequest}). Ambos campos son opcionales; si se omiten, se verifica la
 * bitácora completa.
 *
 * @param fromId id inicial inclusivo a verificar, o {@code null} desde el principio
 * @param toId id final inclusivo a verificar, o {@code null} hasta el último registro
 */
public record AuditVerifyRequest(
        Long fromId,
        Long toId
) {
}
