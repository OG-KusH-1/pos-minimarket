package cl.minimarket.pos.inventory.application.adjustment;

/**
 * Motivos permitidos para solicitar un ajuste manual de stock ({@code MODELO_DATOS.md} B.14).
 *
 * <p>Los nombres coinciden exactamente con el {@code CHECK} de
 * {@code stock_adjustments.reason_code}.
 */
public enum AdjustmentReason {
    /** Corrección por diferencia detectada en conteo o revisión. */
    COUNT_CORRECTION,
    /** Corrección por error operativo o de sistema. */
    SYSTEM_ERROR,
    /** Mercadería encontrada en bodega o sala que no estaba registrada. */
    FOUND_GOODS,
    /** Mercadería faltante no explicada por venta o merma tipificada. */
    MISSING_GOODS,
    /** Regularización de cantidades entre lotes o fechas de vencimiento. */
    LOT_CORRECTION,
    /** Otro motivo documentado en las observaciones ({@code notes}). */
    OTHER
}
