package cl.minimarket.pos.reporting.sales.application;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Criterios de agrupación admitidos por {@code GET /api/v1/reports/sales} ({@code API.md} F.2).
 */
public enum SalesGroupBy {
    DAY,
    HOUR,
    WEEKDAY,
    CATEGORY,
    STORE;

    /**
     * Convierte el parámetro HTTP {@code groupBy} (insensible a mayúsculas) al valor del enum; si
     * viene nulo o en blanco devuelve {@link #DAY}.
     *
     * @param raw valor recibido en la query string
     * @return criterio de agrupación validado
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si el valor no corresponde a ninguno
     *         de los admitidos
     */
    public static SalesGroupBy fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return DAY;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "DAY" -> DAY;
            case "HOUR" -> HOUR;
            case "WEEKDAY" -> WEEKDAY;
            case "CATEGORY" -> CATEGORY;
            case "STORE" -> STORE;
            default -> throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Criterio de agrupación inválido para el reporte de ventas: " + raw,
                    Map.of(),
                    List.of(new FieldErrorDetail(
                            "groupBy",
                            "INVALID_VALUE",
                            "Valores permitidos: day, hour, weekday, category, store.")));
        };
    }
}
