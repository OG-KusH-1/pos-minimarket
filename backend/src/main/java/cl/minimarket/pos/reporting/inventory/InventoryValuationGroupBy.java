package cl.minimarket.pos.reporting.inventory;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Criterio de agrupación admitido por el reporte de valorización de inventario ({@code API.md} F.2).
 */
@Schema(name = "InventoryValuationGroupBy", description = "Criterio de agrupación de valorización de inventario.")
public enum InventoryValuationGroupBy {
    CATEGORY("category"),
    STORE("store"),
    PRODUCT("product");

    private final String paramValue;

    InventoryValuationGroupBy(String paramValue) {
        this.paramValue = paramValue;
    }

    /** Valor textual en minúsculas documentado en {@code API.md} F.2. */
    public String paramValue() {
        return paramValue;
    }

    /**
     * Convierte el parámetro HTTP {@code groupBy} a su enum, usando {@link #CATEGORY} por defecto
     * cuando se omite o viene en blanco.
     *
     * @param raw valor recibido en el query param {@code groupBy}
     * @return agrupación resuelta
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si el valor no es válido
     */
    public static InventoryValuationGroupBy fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return CATEGORY;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (InventoryValuationGroupBy value : values()) {
            if (value.paramValue.equals(normalized)) {
                return value;
            }
        }
        throw new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "Valor inválido para groupBy: '" + raw + "'. Valores permitidos: category, store, product.",
                Map.of(),
                List.of(new FieldErrorDetail("groupBy", "INVALID_VALUE",
                        "Valores permitidos: category, store, product.")));
    }
}
