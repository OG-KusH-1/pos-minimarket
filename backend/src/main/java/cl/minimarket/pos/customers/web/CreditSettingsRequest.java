package cl.minimarket.pos.customers.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Solicitud para habilitar o modificar el cupo de crédito de un cliente ({@code API.md} F.3).
 */
@Schema(name = "CustomerCreditSettingsRequest", description = "Configuración de crédito (fiado) del cliente.")
public record CreditSettingsRequest(
        @NotNull
        Boolean creditEnabled,

        @NotNull
        @DecimalMin("0")
        BigDecimal creditLimit,

        @NotBlank
        @Size(min = 3, max = 250)
        String reason
) {
}
