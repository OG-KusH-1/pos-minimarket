package cl.minimarket.pos.sales.web.pos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Línea de entrada del carrito en solicitudes de cotización o venta
 * ({@code API.md} F.3 {@code CartLineInput}).
 *
 * @param productId      identificador del producto
 * @param quantity       cantidad solicitada (> 0 y ≤ 9999)
 * @param scannedCode    código escaneado si proviene de lectura (opcional)
 * @param embeddedPrice  precio bruto embebido desde etiqueta de balanza (opcional)
 * @param manualDiscount descuento manual de línea (opcional)
 */
public record CartLineInput(
        @NotNull(message = "El identificador del producto es obligatorio.")
        Long productId,

        @NotNull(message = "La cantidad es obligatoria.")
        @DecimalMin(value = "0.001", message = "La cantidad debe ser mayor que 0.")
        @DecimalMax(value = "9999", message = "La cantidad no puede superar 9999.")
        BigDecimal quantity,

        @Size(max = 20, message = "El código escaneado no puede superar 20 caracteres.")
        String scannedCode,

        @DecimalMin(value = "0", message = "El precio embebido no puede ser negativo.")
        BigDecimal embeddedPrice,

        @Valid
        DiscountInput manualDiscount
) {
}
