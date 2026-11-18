package cl.minimarket.pos.organization.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO de creación y edición de una terminal POS ({@code API.md} F.3 {@code TerminalRequest}).
 */
public record TerminalRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9-]{2,20}$") String code,
        @NotBlank @Size(min = 2, max = 80) String name
) {
}
