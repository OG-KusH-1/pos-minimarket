package cl.minimarket.pos.shared.context;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Obtiene el id del usuario autenticado de la request actual sin acoplar shared a security.
 *
 * <p>Contrato (para T21): el id se toma, en este orden, de
 * <ol>
 *   <li>un principal que implemente {@link UserIdentity}; o</li>
 *   <li>{@code Authentication.getName()} si es un entero positivo (convención: el {@code sub} del
 *       JWT es el id de {@code users}).</li>
 * </ol>
 * Si no hay autenticación, es anónima o no cumple ninguna de las dos reglas, devuelve vacío. Lo
 * usan {@link StoreContextFilter}, el filtro de idempotencia y el {@code AuditorAware}.
 *
 * <p>Es una utilidad estática porque lee el {@code SecurityContextHolder}, que ya es estado del
 * hilo; el resolvedor no tiene estado propio.
 */
public final class CurrentUserId {

    private CurrentUserId() {
    }

    /**
     * @return id del usuario autenticado, o vacío si no hay uno identificable
     */
    public static Optional<Long> find() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Indica si la request tiene una autenticación real (no nula, autenticada y no anónima),
     * independientemente de que se pueda extraer el id. Permite distinguir "sin sesión" de
     * "sesión cuyo principal no cumple el contrato de id" (esta última es un error de configuración
     * que debe fallar cerrado).
     *
     * @return {@code true} si hay un usuario autenticado
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * Variante sin acceso al contexto global, útil en pruebas.
     *
     * @param authentication autenticación a interpretar; puede ser nula
     * @return id del usuario, o vacío
     */
    public static Optional<Long> of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof UserIdentity identity && identity.userId() != null) {
            return Optional.of(identity.userId());
        }
        return parsePositiveLong(authentication.getName());
    }

    private static Optional<Long> parsePositiveLong(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            long value = Long.parseLong(text.trim());
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
