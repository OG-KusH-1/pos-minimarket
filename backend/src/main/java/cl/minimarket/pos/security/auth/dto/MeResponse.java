package cl.minimarket.pos.security.auth.dto;

import cl.minimarket.pos.security.auth.TokenScope;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Perfil completo del usuario autenticado ({@code GET /api/v1/auth/me}, API.md F.3).
 *
 * @param id identificador del usuario
 * @param username nombre de usuario
 * @param fullName nombre completo
 * @param roles códigos de roles asignados
 * @param permissions códigos de permisos efectivos (filtrados por {@code scope} si aplica)
 * @param stores locales activos accesibles
 * @param defaultStoreId identificador del local por defecto, o {@code null} si no tiene
 * @param limits límites efectivos por rol ({@code null} significa sin límite / infinito; clave ausente equivale a 0)
 * @param scope alcance del token actual ({@code FULL} o {@code POS})
 * @param hasPin indica si el usuario tiene PIN configurado
 */
public record MeResponse(
        Long id,
        String username,
        String fullName,
        List<String> roles,
        List<String> permissions,
        List<StoreSummary> stores,
        Long defaultStoreId,
        Map<String, BigDecimal> limits,
        TokenScope scope,
        boolean hasPin
) {
}
