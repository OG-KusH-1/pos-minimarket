package cl.minimarket.pos.reporting.shrinkage;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Criterio de agrupación admitido por el reporte de mermas ({@code API.md} F.2).
 */
@Schema(name = "ShrinkageGroupBy", description = "Criterio de agrupación del reporte de mermas.")
public enum ShrinkageGroupBy {
    TYPE("type"),
    PRODUCT("product"),
    CATEGORY("category"),
    STORE("store"),
    MONTH("month");

    private final String paramValue;

    ShrinkageGroupBy(String paramValue) {
        this.paramValue = paramValue;
    }

    /** Valor textual en minúsculas documentado en {@code API.md} F.2. */
    public String paramValue() {
        return paramValue;
    }

    /**
     * Convierte el parámetro HTTP {@code groupBy} a su enum, usando {@link #TYPE} por defecto
     * cuando se omite o viene en blanco.
     *
     * @param raw valor recibido en el query param {@code groupBy}
     * @return agrupación resuelta
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si el valor no es válido
     */
    public static ShrinkageGroupBy fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return TYPE;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ShrinkageGroupBy value : values()) {
            if (value.paramValue.equals(normalized)) {
                return value;
            }
        }
        throw new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "Valor inválido para groupBy: '" + raw
                        + "'. Valores permitidos: type, product, category, store, month.",
                Map.of(),
                List.of(new FieldErrorDetail("groupBy", "INVALID_VALUE",
                        "Valores permitidos: type, product, category, store, month.")));
    }
}
