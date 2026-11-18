package cl.minimarket.pos.cash.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Solicitud de apertura de turno de caja ({@code POST /cash-sessions}).
 *
 * @param openingAmount monto inicial en efectivo (CLP, {@code >= 0})
 */
public record OpenCashSessionRequest(
        @NotNull(message = "El monto de apertura es obligatorio.")
        @DecimalMin(value = "0", message = "El monto de apertura debe ser mayor o igual a 0.")
        BigDecimal openingAmount
) {
}
