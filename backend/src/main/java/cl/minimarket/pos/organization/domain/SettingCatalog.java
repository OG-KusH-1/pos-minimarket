package cl.minimarket.pos.organization.domain;

import cl.minimarket.pos.organization.SettingScope;
import cl.minimarket.pos.organization.SettingValueType;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catálogo oficial de claves de {@code settings} según {@code MODELO_DATOS.md} B.1.
 *
 * <p>Define tipo, rango (Mín/Máx) y alcance permitido ({@code GLOBAL} o {@code GLOBAL+STORE}) para
 * validar cada actualización en {@code PUT /api/v1/settings/{key}}.
 */
public final class SettingCatalog {

    private static final int MAX_TICKET_LINES = 6;
    private static final int MAX_TICKET_LINE_LENGTH = 48;

    /**
     * Definición de una clave de configuración.
     */
    public record Definition(
            String key,
            SettingValueType valueType,
            boolean storeOverrideAllowed,
            Long min,
            Long max,
            String description
    ) {
        /** Valida que el alcance solicitado sea compatible con esta clave. */
        public void validateScope(SettingScope scope) {
            if (scope == SettingScope.STORE && !storeOverrideAllowed) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "La clave '" + key + "' solo admite alcance GLOBAL.",
                        Map.of(),
                        List.of(new FieldErrorDetail("scope", "GLOBAL_ONLY",
                                "Esta clave no puede sobreescribirse por local.")));
            }
        }

        /** Valida que el valor JSON cumpla el tipo y el rango/formato de B.1. */
        public void validateValue(JsonNode value) {
            if (value == null || value.isNull()) {
                throw invalidValue("INVALID_TYPE", "El valor no puede ser nulo.");
            }
            switch (valueType) {
                case BOOLEAN -> {
                    if (!value.isBoolean()) {
                        throw invalidValue("INVALID_TYPE", "Se esperaba un valor booleano (true/false).");
                    }
                }
                case NUMBER -> {
                    if (!value.isNumber() || !value.isIntegralNumber()) {
                        throw invalidValue("INVALID_TYPE", "Se esperaba un número entero.");
                    }
                    BigDecimal numeric = value.decimalValue();
                    if (min != null && numeric.compareTo(BigDecimal.valueOf(min)) < 0) {
                        throw invalidValue("OUT_OF_RANGE",
                                "El valor debe ser mayor o igual a " + min + ".");
                    }
                    if (max != null && numeric.compareTo(BigDecimal.valueOf(max)) > 0) {
                        throw invalidValue("OUT_OF_RANGE",
                                "El valor debe ser menor o igual a " + max + ".");
                    }
                }
                case JSON -> validateTicketLines(value);
                case STRING -> {
                    if (!value.isString()) {
                        throw invalidValue("INVALID_TYPE", "Se esperaba una cadena de texto.");
                    }
                }
            }
        }

        private void validateTicketLines(JsonNode value) {
            if (!value.isArray()) {
                throw invalidValue("INVALID_TYPE", "Se esperaba un arreglo JSON de líneas de texto.");
            }
            if (value.size() > MAX_TICKET_LINES) {
                throw invalidValue("OUT_OF_RANGE",
                        "Se permiten como máximo " + MAX_TICKET_LINES + " líneas.");
            }
            for (int i = 0; i < value.size(); i++) {
                JsonNode item = value.get(i);
                if (!item.isString()) {
                    throw invalidValue("INVALID_TYPE",
                            "La línea " + (i + 1) + " debe ser texto.");
                }
                if (item.asString().length() > MAX_TICKET_LINE_LENGTH) {
                    throw invalidValue("OUT_OF_RANGE",
                            "La línea " + (i + 1) + " supera los " + MAX_TICKET_LINE_LENGTH + " caracteres.");
                }
            }
        }

        private BusinessException invalidValue(String code, String message) {
            return new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Valor inválido para '" + key + "': " + message,
                    Map.of(),
                    List.of(new FieldErrorDetail("value", code, message)));
        }
    }

    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(
            entry("pos.cash_rounding.enabled", SettingValueType.BOOLEAN, true, null, null,
                    "Redondeo legal del efectivo al múltiplo de 10 (RN-08)."),
            entry("pos.suspended.max_per_terminal", SettingValueType.NUMBER, true, 1L, 20L,
                    "Máximo de ventas suspendidas por terminal (1 a 20)."),
            entry("pos.suspended.ttl_hours", SettingValueType.NUMBER, true, 1L, 72L,
                    "Horas de vida de una venta suspendida antes de expirar (1 a 72)."),
            entry("pos.return.max_days", SettingValueType.NUMBER, true, 1L, 365L,
                    "Días máximos desde la venta para aceptar una devolución (1 a 365)."),
            entry("ticket.header_lines", SettingValueType.JSON, true, null, null,
                    "Líneas del encabezado del ticket (máx. 6 líneas de 48 caracteres)."),
            entry("ticket.footer_lines", SettingValueType.JSON, true, null, null,
                    "Líneas del pie del ticket (máx. 6 líneas de 48 caracteres)."),
            entry("ticket.show_tax_breakdown", SettingValueType.BOOLEAN, true, null, null,
                    "Mostrar el desglose de impuestos en el ticket."),
            entry("ticket.paper_width_mm", SettingValueType.NUMBER, true, 58L, 80L,
                    "Ancho del papel térmico en mm (58 a 80)."),
            entry("alerts.expiry.days_ahead", SettingValueType.NUMBER, true, 1L, 90L,
                    "Días de anticipación para las alertas de vencimiento (1 a 90)."),
            entry("cash.difference_tolerance", SettingValueType.NUMBER, true, 0L, 50_000L,
                    "Diferencia de cierre de caja tolerada sin aprobación, en CLP (0 a 50000)."),
            entry("cash.default_opening_amount", SettingValueType.NUMBER, true, 0L, 1_000_000L,
                    "Monto de apertura de turno sugerido, en CLP (0 a 1000000)."),
            entry("security.password.min_length", SettingValueType.NUMBER, false, 8L, 64L,
                    "Largo mínimo de la contraseña (8 a 64)."),
            entry("security.lockout.max_attempts", SettingValueType.NUMBER, false, 3L, 20L,
                    "Intentos fallidos de clave antes del bloqueo (3 a 20)."),
            entry("security.lockout.minutes", SettingValueType.NUMBER, false, 1L, 1440L,
                    "Minutos de bloqueo de la cuenta por intentos fallidos (1 a 1440)."),
            entry("security.pin.max_attempts", SettingValueType.NUMBER, false, 3L, 10L,
                    "Intentos fallidos de PIN antes del bloqueo (3 a 10)."),
            entry("security.pin.lockout_minutes", SettingValueType.NUMBER, false, 1L, 1440L,
                    "Minutos de bloqueo del PIN (1 a 1440)."),
            entry("security.refresh.ttl_hours", SettingValueType.NUMBER, false, 1L, 72L,
                    "Vida deslizante del refresh token, en horas (1 a 72)."),
            entry("security.refresh.absolute_days", SettingValueType.NUMBER, false, 1L, 30L,
                    "Tope absoluto de la sesión, en días (1 a 30)."),
            entry("email.ticket.enabled", SettingValueType.BOOLEAN, true, null, null,
                    "Permitir el envío del ticket por correo.")
    );

    private SettingCatalog() {
    }

    private static Map.Entry<String, Definition> entry(
            String key, SettingValueType type, boolean storeOverrideAllowed,
            Long min, Long max, String description) {
        return Map.entry(key, new Definition(key, type, storeOverrideAllowed, min, max, description));
    }

    /** Busca la definición de una clave conocida. */
    public static Optional<Definition> find(String key) {
        return Optional.ofNullable(DEFINITIONS.get(key));
    }

    /** Obtiene la definición de una clave o lanza 404 {@code NOT_FOUND}. */
    public static Definition require(String key) {
        return find(key).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Parámetro de configuración no encontrado: " + key));
    }
}
