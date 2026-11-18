package cl.minimarket.pos.security.auth.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Utilidad para extraer la dirección IP y el {@code User-Agent} de la solicitud HTTP.
 */
public final class ClientIpResolver {

    private static final int MAX_USER_AGENT_LENGTH = 300;

    private ClientIpResolver() {
    }

    /**
     * Obtiene la IP del cliente considerando {@code X-Forwarded-For} si está presente y en blanco
     * no vacío, o {@link HttpServletRequest#getRemoteAddr()} en caso contrario.
     */
    public static String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr.trim() : null;
    }

    /**
     * Obtiene el encabezado {@code User-Agent} truncado al máximo permitido por {@code refresh_tokens}.
     */
    public static String resolveUserAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return null;
        }
        return ua.length() <= MAX_USER_AGENT_LENGTH ? ua : ua.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
