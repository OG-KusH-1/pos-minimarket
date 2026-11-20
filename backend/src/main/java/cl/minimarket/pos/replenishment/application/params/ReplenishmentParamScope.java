package cl.minimarket.pos.replenishment.application.params;

/**
 * Alcance de una fila de {@code replenishment_params} ({@code MODELO_DATOS.md} B.11 y
 * {@code PLAN.md} C.2). La resolución va del más específico al más general: producto, luego
 * categoría (subcategoría y después categoría padre), luego local, luego global.
 *
 * <p>La coherencia entre el alcance y sus claves foráneas la valida la restricción
 * {@code ck_replenishment_params_scope_fk}: GLOBAL no lleva ids, STORE exige local, CATEGORY
 * exige categoría (con local opcional) y PRODUCT exige producto (con local opcional y sin
 * categoría).
 */
public enum ReplenishmentParamScope {
    /** Default de toda la empresa: no admite local, categoría ni producto. */
    GLOBAL,
    /** Override para un local. */
    STORE,
    /** Override para una categoría (con local opcional). */
    CATEGORY,
    /** Override para un producto (con local opcional). */
    PRODUCT
}
