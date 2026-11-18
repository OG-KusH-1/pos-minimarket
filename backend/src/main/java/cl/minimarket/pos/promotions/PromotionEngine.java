package cl.minimarket.pos.promotions;

/**
 * Puerto público del módulo {@code promotions} para evaluar promociones vigentes sobre un carrito
 * de venta ({@code PLAN.md} A.4).
 *
 * <p>En Fase 1 usa la implementación no-op ({@code NoopPromotionEngine}); en Fase 5 se reemplaza
 * por el motor completo de reglas de {@code MODELO_DATOS.md} B.10.
 */
public interface PromotionEngine {

    /**
     * Evalúa las promociones vigentes para el contexto de carrito indicado.
     *
     * @param context contexto con local, instante, cliente y líneas del carrito
     * @return resultado con los descuentos promocionales por línea (nunca nulo)
     */
    PromotionResult evaluate(PricingContext context);
}
