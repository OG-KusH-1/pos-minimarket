package cl.minimarket.pos.security.users;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * Respuesta combinada de creación de usuario ({@code UserResponse + TemporaryPasswordResponse} en
 * {@code API.md} F.2).
 */
public record UserCreatedResponse(
        @JsonUnwrapped UserResponse user,
        String temporaryPassword,
        boolean mustChangePassword
) {
}
