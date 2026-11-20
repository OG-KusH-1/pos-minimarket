package cl.minimarket.pos.replenishment.domain;

/**
 * Indicadores de calidad de datos o de ajustes aplicados durante el cálculo de reabastecimiento.
 *
 * <p>Los nombres coinciden exactamente con el enum {@code replenishment_flag} del modelo de datos
 * y con el CHECK de {@code replenishment_lines}, de modo que la capa de persistencia puede guardar
 * directamente {@link Enum#name()} sin traducciones.</p>
 */
public enum ReplenishmentFlag {
    NEW_PRODUCT,
    SHORT_HISTORY,
    CENSORED_DAYS,
    OUTLIERS_CAPPED,
    SHORT_SHELF_LIFE,
    CAPPED_BY_UPPER,
    ZERO_DEMAND,
    MOQ_APPLIED,
    LEAD_TIME_ZERO
}
