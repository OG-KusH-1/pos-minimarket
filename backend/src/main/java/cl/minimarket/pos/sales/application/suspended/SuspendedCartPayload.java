package cl.minimarket.pos.sales.application.suspended;

import cl.minimarket.pos.sales.application.pricing.DiscountKind;

import java.math.BigDecimal;
import java.util.List;

/**
 * Estructura JSONB almacenada en la columna {@code suspended_sales.cart}.
 *
 * @param schemaVersion versión de esquema para evolucionar compatibilidad hacia atrás
 * @param lines líneas guardadas del carrito
 * @param totalDiscount descuento manual sobre el total del carrito, si existe
 * @param estimatedTotal total estimado calculado al suspender (respaldo si algún producto cambia)
 */
record SuspendedCartPayload(
        int schemaVersion,
        List<LinePayload> lines,
        DiscountPayload totalDiscount,
        BigDecimal estimatedTotal
) {

    static final int CURRENT_SCHEMA_VERSION = 1;

    record LinePayload(
            Long productId,
            BigDecimal quantity,
            String scannedCode,
            BigDecimal embeddedPrice,
            DiscountPayload manualDiscount
    ) {
    }

    record DiscountPayload(
            DiscountKind kind,
            BigDecimal value,
            String reason
    ) {
    }
}
