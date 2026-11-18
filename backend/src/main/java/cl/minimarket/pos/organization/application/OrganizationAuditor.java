package cl.minimarket.pos.organization.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Punto único de auditoría para el módulo {@code organization} (ROLES_PERMISOS D.5).
 *
 * <p>Delega en {@link AuditService#record(AuditEvent)} dentro de la transacción activa del
 * servicio llamador para persistir {@code COMPANY_UPDATED}, {@code STORE_CHANGED} y
 * {@code SETTING_CHANGED} en {@code audit_log}.
 */
@Component
public class OrganizationAuditor {

    private final AuditService auditService;

    public OrganizationAuditor(AuditService auditService) {
        this.auditService = auditService;
    }

    /** Registra el evento {@code COMPANY_UPDATED}. */
    public void companyUpdated(Long companyId, Object oldValue, Object newValue) {
        auditService.record(new AuditEvent(
                "COMPANY_UPDATED",
                "company",
                String.valueOf(companyId),
                oldValue,
                newValue,
                null,
                null
        ));
    }

    /** Registra el evento {@code STORE_CHANGED} (creación, edición, activación o desactivación). */
    public void storeChanged(Long storeId, String changeType, Object oldValue, Object newValue) {
        auditService.record(new AuditEvent(
                "STORE_CHANGED",
                "store",
                String.valueOf(storeId),
                oldValue,
                newValue,
                changeType,
                null
        ));
    }

    /** Registra el evento {@code SETTING_CHANGED}. */
    public void settingChanged(String key, String scope, Long storeId, Object oldValue, Object newValue) {
        auditService.record(new AuditEvent(
                "SETTING_CHANGED",
                "setting",
                key,
                settingPayload(scope, storeId, oldValue),
                settingPayload(scope, storeId, newValue),
                null,
                null
        ));
    }

    private static Map<String, Object> settingPayload(String scope, Long storeId, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope);
        payload.put("storeId", storeId);
        payload.put("value", value);
        return payload;
    }
}
