package cl.minimarket.pos.customers.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Solicitud de ajuste manual de cuenta corriente ({@code API.md} F.3).
 */
@Schema(name = "CustomerAdjustmentRequest", description = "Ajuste manual de deuda en cuenta corriente.")
public record CustomerAdjustmentRequest(
        @NotNull
        BigDecimal amount,

        @NotBlank
        @Size(min = 3, max = 250)
        String reason
) {
}
