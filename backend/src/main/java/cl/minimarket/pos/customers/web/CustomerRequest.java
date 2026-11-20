package cl.minimarket.pos.customers.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de creación o actualización de cliente ({@code API.md} F.3).
 */
@Schema(name = "CustomerRequest", description = "Datos para crear o actualizar un cliente.")
public record CustomerRequest(
        @Size(max = 20)
        String rut,

        @NotBlank
        @Size(min = 2, max = 150)
        String name,

        @Email
        @Size(max = 254)
        String email,

        @Size(max = 30)
        String phone,

        @Size(max = 250)
        String address,

        @Size(max = 2000)
        String notes,

        Long version
) {
}
