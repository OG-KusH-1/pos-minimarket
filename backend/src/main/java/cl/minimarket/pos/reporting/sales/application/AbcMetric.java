package cl.minimarket.pos.reporting.sales.application;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Métrica de Pareto para {@code GET /api/v1/reports/abc} ({@code API.md} F.2).
 */
public enum AbcMetric {
    AMOUNT,
    QTY,
    MARGIN;

    /**
     * Convierte el parámetro HTTP {@code metric} (insensible a mayúsculas); si viene nulo o en
     * blanco devuelve {@link #AMOUNT}.
     *
     * @param raw valor recibido en la query string
     * @return métrica validada
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si el valor es inválido
     */
    public static AbcMetric fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return AMOUNT;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "AMOUNT" -> AMOUNT;
            case "QTY" -> QTY;
            case "MARGIN" -> MARGIN;
            default -> throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Métrica inválida para el análisis ABC: " + raw,
                    Map.of(),
                    List.of(new FieldErrorDetail(
                            "metric",
                            "INVALID_VALUE",
                            "Valores permitidos: amount, qty, margin.")));
        };
    }
}
