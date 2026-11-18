package cl.minimarket.pos.sales.application.suspended;

import cl.minimarket.pos.sales.application.pricing.DiscountKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Modelos de entrada y salida de la capa de aplicación para ventas suspendidas.
 */
public final class SuspendedSaleModels {

    private SuspendedSaleModels() {
    }

    /**
     * Descuento manual preservado dentro de un carrito suspendido.
     *
     * @param kind tipo de descuento
     * @param value porcentaje o monto CLP
     * @param reason motivo opcional
     */
    public record DiscountData(
            DiscountKind kind,
            BigDecimal value,
            String reason
    ) {
        public DiscountData {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Línea preservada en un carrito suspendido.
     *
     * @param productId identificador del producto
     * @param quantity cantidad solicitada
     * @param scannedCode código escaneado opcional
     * @param embeddedPrice precio embebido de balanza opcional
     * @param manualDiscount descuento manual de línea opcional
     */
    public record CartLineData(
            Long productId,
            BigDecimal quantity,
            String scannedCode,
            BigDecimal embeddedPrice,
            DiscountData manualDiscount
    ) {
        public CartLineData {
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(quantity, "quantity");
        }
    }

    /**
     * Carrito estructurado asociado a una venta suspendida.
     *
     * @param lines líneas del carrito
     * @param totalDiscount descuento manual al total opcional
     */
    public record CartData(
            List<CartLineData> lines,
            DiscountData totalDiscount
    ) {
        public CartData {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * Comando para suspender el carrito actual de la terminal activa.
     *
     * @param label etiqueta descriptiva opcional (máximo 60 caracteres)
     * @param customerId cliente asociado opcional
     * @param cart carrito a preservar
     */
    public record SuspendCommand(
            String label,
            Long customerId,
            CartData cart
    ) {
        public SuspendCommand {
            Objects.requireNonNull(cart, "cart");
        }
    }

    /**
     * Vista completa de una venta suspendida devuelta al listar, crear o retomar.
     *
     * @param id identificador único
     * @param label etiqueta descriptiva
     * @param userName nombre visible del cajero que suspendió la venta
     * @param customerId identificador del cliente asociado, si existe
     * @param cart carrito guardado
     * @param itemCount cantidad de líneas del carrito
     * @param estimatedTotal total estimado calculado en CLP
     * @param createdAt instante de creación
     * @param expiresAt instante de expiración
     */
    public record SuspendedSaleDetail(
            Long id,
            String label,
            String userName,
            Long customerId,
            CartData cart,
            int itemCount,
            BigDecimal estimatedTotal,
            Instant createdAt,
            Instant expiresAt
    ) {
    }
}
