package cl.minimarket.pos.cash.web;

import cl.minimarket.pos.cash.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Solicitud de cierre de turno con arqueo por medio de pago ({@code POST /cash-sessions/{id}/close}).
 *
 * @param counts lista de montos contados por medio de pago (debe incluir {@code CASH} y todo medio con movimiento)
 * @param notes observaciones opcionales de cierre (máx. 500 caracteres)
 */
public record CloseCashSessionRequest(
        @NotEmpty(message = "Debe informar al menos el arqueo de efectivo.")
        @Valid
        List<CountInput> counts,

        @Size(max = 500, message = "Las notas no pueden superar los 500 caracteres.")
        String notes
) {
    /** Arqueo individual de un medio de pago. */
    public record CountInput(
            @NotNull(message = "El medio de pago es obligatorio.")
            PaymentMethod paymentMethod,

            @NotNull(message = "El monto contado es obligatorio.")
            @DecimalMin(value = "0", message = "El monto contado debe ser mayor o igual a 0.")
            BigDecimal countedAmount,

            Map<Integer, Integer> denominationDetail,

            @Size(max = 250, message = "Las notas del medio no pueden superar los 250 caracteres.")
            String notes
    ) {
    }
}
