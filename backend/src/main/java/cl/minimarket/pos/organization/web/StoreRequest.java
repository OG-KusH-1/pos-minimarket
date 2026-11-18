package cl.minimarket.pos.organization.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO de creación y edición de un local ({@code API.md} F.3 {@code StoreRequest}).
 */
public record StoreRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9]{2,10}$") String code,
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotBlank @Size(max = 250) String address,
        @NotBlank @Size(max = 100) String commune,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 30) String phone,
        @Size(max = 20) String siiBranchCode
) {
}
