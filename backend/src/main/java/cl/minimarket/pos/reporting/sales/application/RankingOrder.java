package cl.minimarket.pos.reporting.sales.application;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dirección del ranking en {@code GET /api/v1/reports/product-ranking} ({@code API.md} F.2).
 */
public enum RankingOrder {
    MOST,
    LEAST;

    /**
     * Convierte el parámetro HTTP {@code order} (insensible a mayúsculas); si viene nulo o en
     * blanco devuelve {@link #MOST}.
     *
     * @param raw valor recibido en la query string
     * @return dirección validada
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si el valor es inválido
     */
    public static RankingOrder fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return MOST;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "MOST" -> MOST;
            case "LEAST" -> LEAST;
            default -> throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Orden inválido para el ranking de productos: " + raw,
                    Map.of(),
                    List.of(new FieldErrorDetail(
                            "order",
                            "INVALID_VALUE",
                            "Valores permitidos: most, least.")));
        };
    }
}
