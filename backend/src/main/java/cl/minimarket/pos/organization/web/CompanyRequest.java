package cl.minimarket.pos.organization.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO de actualización de los datos de la empresa ({@code API.md} F.3 {@code CompanyRequest}).
 */
public record CompanyRequest(
        @NotBlank @Size(max = 15) String rut,
        @NotBlank @Size(min = 2, max = 200) String legalName,
        @NotBlank @Size(min = 2, max = 200) String tradeName,
        @NotBlank @Size(min = 2, max = 200) String businessActivity,
        @NotBlank @Size(max = 250) String address,
        @NotBlank @Size(max = 100) String commune,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 30) String phone,
        @Email @Size(max = 254) String email,
        Long logoFileId,
        @NotBlank @Size(max = 40) String timezone
) {
}
