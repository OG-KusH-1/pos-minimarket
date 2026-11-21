package cl.minimarket.pos.reporting.margin;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Criterio de agrupación admitido por el reporte de margen y utilidad ({@code API.md} F.2).
 */
@Schema(name = "MarginGroupBy", description = "Criterio de agrupación del reporte de margen.")
public enum MarginGroupBy {
    PRODUCT("product"),
    CATEGORY("category"),
    BRAND("brand"),
    DAY("day");

    private final String paramValue;

    MarginGroupBy(String paramValue) {
        this.paramValue = paramValue;
    }

    /** Valor textual en minúsculas documentado en {@code API.md} F.2. */
    public String paramValue() {
        return paramValue;
    }

    /**
     * Convierte el parámetro HTTP {@code groupBy} a su enum, admitiendo valor vacío (por defecto
     * {@link #PRODUCT}) y comparación insensible a mayúsculas.
     *
     * @param raw valor recibido en el query param {@code groupBy}
     * @return agrupación resuelta
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si el valor no corresponde a ninguno de
     *         los permitidos
     */
    public static MarginGroupBy fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return PRODUCT;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (MarginGroupBy value : values()) {
            if (value.paramValue.equals(normalized)) {
                return value;
            }
        }
        throw new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "Valor inválido para groupBy: '" + raw + "'. Valores permitidos: product, category, brand, day.",
                Map.of(),
                List.of(new FieldErrorDetail("groupBy", "INVALID_VALUE",
                        "Valores permitidos: product, category, brand, day.")));
    }
}
