package cl.minimarket.pos.cash.web;

import cl.minimarket.pos.cash.CashMovementType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Solicitud de ingreso o retiro manual de efectivo en un turno ({@code POST /cash-sessions/{id}/movements}).
 *
 * @param movementType tipo de movimiento ({@code CASH_IN} o {@code CASH_OUT})
 * @param amount monto en CLP ({@code >= 1})
 * @param reason motivo del movimiento (3 a 250 caracteres)
 */
public record CashMovementRequest(
        @NotNull(message = "El tipo de movimiento es obligatorio.")
        CashMovementType movementType,

        @NotNull(message = "El monto es obligatorio.")
        @DecimalMin(value = "1", message = "El monto debe ser al menos 1.")
        BigDecimal amount,

        @NotBlank(message = "El motivo es obligatorio.")
        @Size(min = 3, max = 250, message = "El motivo debe tener entre 3 y 250 caracteres.")
        String reason
) {
}
