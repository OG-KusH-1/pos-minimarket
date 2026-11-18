package cl.minimarket.pos.security.users;

import cl.minimarket.pos.security.auth.dto.StoreSummary;

import java.time.Instant;
import java.util.List;

/**
 * Respuesta detallada de usuario ({@code API.md} F.3).
 */
public record UserResponse(
        Long id,
        String username,
        String fullName,
        String email,
        String rut,
        boolean active,
        boolean locked,
        Instant lockedUntil,
        boolean hasPin,
        List<RoleSummary> roles,
        List<StoreSummary> stores,
        Long defaultStoreId,
        Instant lastLoginAt,
        Instant createdAt,
        Long version
) {
}
