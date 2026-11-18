package cl.minimarket.pos.organization;

/**
 * API pública de parámetros de configuración del módulo {@code organization} (PLAN A.4).
 *
 * <p>Aplica precedencia: el valor del local ({@code STORE}) tiene prioridad sobre el {@code GLOBAL}.
 */
public interface SettingsApi {

    /**
     * Obtiene el valor efectivo de una clave para el local indicado (o solo el global si
     * {@code storeId} es {@code null}), convertido al tipo solicitado.
     *
     * @param key clave del parámetro (p. ej. {@code pos.cash_rounding.enabled})
     * @param storeId id del local activo, o {@code null} para consultar solo el valor global
     * @param type clase destino ({@code Boolean.class}, {@code Integer.class}, {@code Long.class},
     *        {@code BigDecimal.class}, {@code String.class}, {@code JsonNode.class}, etc.)
     * @param <T> tipo esperado
     * @return valor efectivo convertido
     */
    <T> T get(String key, Long storeId, Class<T> type);
}
