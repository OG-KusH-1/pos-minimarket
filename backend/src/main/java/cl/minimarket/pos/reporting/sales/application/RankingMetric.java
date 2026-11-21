package cl.minimarket.pos.reporting.sales.application;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Métrica de ordenamiento en {@code GET /api/v1/reports/product-ranking} ({@code API.md} F.2).
 */
public enum RankingMetric {
    QTY,
    AMOUNT;

    /**
     * Convierte el parámetro HTTP {@code metric} (insensible a mayúsculas); si viene nulo o en
     * blanco devuelve {@link #QTY}.
     *
     * @param raw valor recibido en la query string
     * @return métrica validada
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si el valor es inválido
     */
    public static RankingMetric fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return QTY;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "QTY" -> QTY;
            case "AMOUNT" -> AMOUNT;
            default -> throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Métrica inválida para el ranking de productos: " + raw,
                    Map.of(),
                    List.of(new FieldErrorDetail(
                            "metric",
                            "INVALID_VALUE",
                            "Valores permitidos: qty, amount.")));
        };
    }
}
