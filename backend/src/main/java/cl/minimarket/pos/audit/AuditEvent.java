package cl.minimarket.pos.audit;

import java.util.Objects;

/**
 * Evento de auditoría a registrar en la bitácora inmutable ({@code audit_log}).
 *
 * <p>El usuario, la IP, el local, la terminal y el {@code traceId} se toman automáticamente del
 * contexto de ejecución activo; quien emite el evento solo aporta los datos propios de la acción
 * de negocio.
 *
 * @param action código de acción auditada (p. ej. {@code PRODUCT_PRICE_CHANGED}; ver D.5)
 * @param entityType tipo de entidad afectada (p. ej. {@code product}, {@code sale}, {@code user})
 * @param entityId identificador de la entidad afectada (en texto)
 * @param oldValue estado previo a la operación (serializable a JSON), o {@code null} si no aplica
 * @param newValue estado posterior o detalle de la operación (serializable a JSON), o {@code null}
 * @param reason motivo o justificación de la operación, o {@code null} si no aplica
 * @param authorizationId id de {@code supervisor_authorizations} cuando medió un override, o {@code null}
 */
public record AuditEvent(
        String action,
        String entityType,
        String entityId,
        Object oldValue,
        Object newValue,
        String reason,
        Long authorizationId
) {

    /** Largo máximo permitido para {@code action} según el esquema de {@code audit_log}. */
    public static final int MAX_ACTION_LENGTH = 60;

    /** Largo máximo permitido para {@code entityType} según el esquema de {@code audit_log}. */
    public static final int MAX_ENTITY_TYPE_LENGTH = 40;

    /** Largo máximo permitido para {@code entityId} según el esquema de {@code audit_log}. */
    public static final int MAX_ENTITY_ID_LENGTH = 40;

    /**
     * Valida que los campos obligatorios de identificación del evento estén presentes, no en
     * blanco y dentro de los largos máximos permitidos en {@code audit_log}.
     *
     * @throws NullPointerException si {@code action}, {@code entityType} o {@code entityId} son nulos
     * @throws IllegalArgumentException si alguno está en blanco o supera su largo máximo
     */
    public AuditEvent {
        action = requireNonBlankWithMaxLength(action, "action", MAX_ACTION_LENGTH);
        entityType = requireNonBlankWithMaxLength(entityType, "entityType", MAX_ENTITY_TYPE_LENGTH);
        entityId = requireNonBlankWithMaxLength(entityId, "entityId", MAX_ENTITY_ID_LENGTH);
    }

    private static String requireNonBlankWithMaxLength(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " no puede ser nulo");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " no puede estar en blanco");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " no puede superar los " + maxLength + " caracteres");
        }
        return value;
    }
}
