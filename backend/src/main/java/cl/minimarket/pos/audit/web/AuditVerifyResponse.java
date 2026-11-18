package cl.minimarket.pos.audit.web;

import cl.minimarket.pos.audit.application.AuditVerificationResult;

/**
 * Resultado de la verificación de la cadena de hash de {@code audit_log}
 * (F.3 {@code AuditVerifyResponse}).
 *
 * @param checked cantidad de registros inspeccionados
 * @param valid {@code true} si la cadena es íntegra en el rango evaluado
 * @param firstBrokenId id del primer registro roto detectado, o {@code null} si {@code valid} es {@code true}
 */
public record AuditVerifyResponse(
        long checked,
        boolean valid,
        Long firstBrokenId
) {

    /**
     * Convierte el resultado de aplicación en su DTO de respuesta HTTP.
     *
     * @param result resultado de la verificación
     * @return DTO de respuesta
     */
    public static AuditVerifyResponse from(AuditVerificationResult result) {
        return new AuditVerifyResponse(result.checked(), result.valid(), result.firstBrokenId());
    }
}
