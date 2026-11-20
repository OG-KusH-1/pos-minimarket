package cl.minimarket.pos.customers.web;

import cl.minimarket.pos.cash.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Solicitud de abono a la deuda de un cliente ({@code API.md} F.3).
 */
@Schema(name = "CustomerPaymentRequest", description = "Registro de abono en cuenta corriente (CASH o TRANSFER).")
public record CustomerPaymentRequest(
        @NotNull
        @DecimalMin("1")
        BigDecimal amount,

        @NotNull
        PaymentMethod paymentMethod,

        @Size(max = 60)
        String reference,

        @Size(max = 250)
        String notes
) {
}
