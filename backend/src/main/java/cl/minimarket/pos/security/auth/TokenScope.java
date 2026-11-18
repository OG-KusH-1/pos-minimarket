package cl.minimarket.pos.security.auth;

/**
 * Alcance del token de acceso emitido tras la autenticación (ROLES_PERMISOS D.4, API.md F.3).
 */
public enum TokenScope {
    /** Sesión completa obtenida con usuario y contraseña. */
    FULL,
    /** Sesión acotada de caja obtenida con PIN ({@code sales:*}, {@code cash:*}, {@code customers:*}). */
    POS
}
