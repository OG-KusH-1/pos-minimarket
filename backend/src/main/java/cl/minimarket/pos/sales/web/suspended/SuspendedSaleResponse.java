package cl.minimarket.pos.sales.web.suspended;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Respuesta pública de una venta suspendida en POS ({@code API.md} F.3).
 *
 * @param id identificador único de la venta suspendida
 * @param label etiqueta descriptiva opcional
 * @param userName nombre del cajero que la suspendió
 * @param customerId identificador del cliente asociado, si existe
 * @param cart contenido del carrito suspendido
 * @param itemCount número de líneas del carrito
 * @param estimatedTotal total estimado en CLP (entero)
 * @param createdAt instante UTC de creación
 * @param expiresAt instante UTC de vencimiento según TTL del local
 */
public record SuspendedSaleResponse(
        Long id,
        String label,
        String userName,
        Long customerId,
        SuspendedSaleCart cart,
        int itemCount,
        BigDecimal estimatedTotal,
        Instant createdAt,
        Instant expiresAt
) {
}
