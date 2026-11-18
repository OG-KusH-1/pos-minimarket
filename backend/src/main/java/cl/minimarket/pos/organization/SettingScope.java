package cl.minimarket.pos.organization;

/**
 * Alcance de un parámetro en {@code settings} (MODELO_DATOS B.1).
 */
public enum SettingScope {
    /** Valor global de toda la empresa ({@code store_id IS NULL}). */
    GLOBAL,
    /** Sobreescritura específica de un local ({@code store_id IS NOT NULL}). */
    STORE
}
