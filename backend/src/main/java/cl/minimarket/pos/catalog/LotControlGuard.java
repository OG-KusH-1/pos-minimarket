package cl.minimarket.pos.catalog;

/**
 * Puerto definido por {@code catalog} e implementado por {@code inventory} para validar y aplicar
 * cambios en la bandera {@code lot_controlled} de un producto (PLAN A.4, RN-04).
 *
 * <p>En la Fase 1, la implementación por defecto permite cambiar {@code lot_controlled} únicamente
 * cuando el stock consolidado de la empresa ({@code StockApi.companyQuantity}) es cero; en caso
 * contrario lanza {@code ENTITY_IN_USE}.
 */
public interface LotControlGuard {

    /**
     * Valida y aplica el cambio de {@code lot_controlled} dentro de la transacción del llamador.
     *
     * @param productId     identificador del producto
     * @param lotControlled nuevo estado deseado de control por lotes
     */
    void apply(Long productId, boolean lotControlled);
}
