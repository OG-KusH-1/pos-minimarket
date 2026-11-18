package cl.minimarket.pos.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Propiedades de CORS del backend (prefijo {@code pos.cors}).
 *
 * <p>Cuando {@code allowedOrigins} está vacío, no se habilita ningún origen externo (en
 * producción nginx sirve el frontend en el mismo origen).
 *
 * @param allowedOrigins orígenes permitidos
 * @param allowedMethods métodos HTTP permitidos
 * @param allowedHeaders cabeceras permitidas en las solicitudes
 * @param exposedHeaders cabeceras expuestas al navegador
 * @param allowCredentials si se permiten credenciales/cookies en CORS
 * @param maxAge duración de caché de respuestas preflight
 */
@ConfigurationProperties(prefix = "pos.cors")
public record CorsProperties(
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue({"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}) List<String> allowedMethods,
        @DefaultValue({
                "Authorization",
                "Content-Type",
                "Idempotency-Key",
                "X-Store-Id",
                "X-Terminal-Id",
                "X-Override-Token"
        }) List<String> allowedHeaders,
        @DefaultValue({"Content-Disposition"}) List<String> exposedHeaders,
        @DefaultValue("true") boolean allowCredentials,
        @DefaultValue("1h") Duration maxAge
) {

    /** Normaliza listas nulas o con cadenas en blanco. */
    public CorsProperties {
        allowedOrigins = sanitize(allowedOrigins);
        allowedMethods = sanitize(allowedMethods);
        allowedHeaders = sanitize(allowedHeaders);
        exposedHeaders = sanitize(exposedHeaders);
    }

    private static List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList();
    }
}
