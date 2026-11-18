package cl.minimarket.pos.inventory.web.stock;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Solicitud de actualización de límites de stock ({@code min_qty} y {@code max_qty}) para un
 * producto en el local activo ({@code API.md} F.3, RN-34).
 */
public record StockLimitsRequest(
        @NotNull(message = "El stock mínimo es obligatorio.")
        @DecimalMin(value = "0", message = "El stock mínimo debe ser mayor o igual a 0.")
        BigDecimal minQty,

        @DecimalMin(value = "0", message = "El stock máximo debe ser mayor o igual a 0.")
        BigDecimal maxQty
) {
}
