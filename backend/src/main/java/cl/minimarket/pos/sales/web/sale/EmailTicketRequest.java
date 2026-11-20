package cl.minimarket.pos.sales.web.sale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de envío del comprobante de venta por correo ({@code POST /sales/{id}/email},
 * {@code API.md} F.3 {@code EmailTicketRequest}).
 *
 * @param email dirección de correo del destinatario (obligatoria)
 */
public record EmailTicketRequest(
        @NotBlank(message = "El correo electrónico es obligatorio.")
        @Email(message = "El correo electrónico no tiene un formato válido.")
        @Size(max = 254, message = "El correo electrónico no puede superar 254 caracteres.")
        String email
) {
}
