package cl.minimarket.pos.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Propiedades de seguridad del módulo {@code security} (prefijo {@code pos.security}).
 *
 * <p>Los valores base viven en {@code application.yml}; los defaults aquí garantizan arranque
 * seguro en pruebas unitarias o perfiles mínimos sin editar el YAML común.
 *
 * @param jwt configuración de emisión y validación de JWT de acceso (ADR-20)
 * @param password política de contraseñas (respaldo si no hay valor en {@code settings})
 */
@ConfigurationProperties(prefix = "pos.security")
public record SecurityProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Password password
) {

    /**
     * Propiedades del JWT de acceso (HS256 con Nimbus).
     *
     * @param secret secreto HMAC-SHA256 de al menos 256 bits (32 bytes en UTF-8)
     * @param issuer identificador del emisor ({@code iss})
     * @param accessTokenTtl tiempo de vida del access token (por defecto 15 minutos)
     */
    public record Jwt(
            @DefaultValue("") String secret,
            @DefaultValue("pos-minimarket") String issuer,
            @DefaultValue("15m") Duration accessTokenTtl
    ) {
    }

    /**
     * Propiedades de política de contraseñas.
     *
     * @param minLength largo mínimo de contraseña por defecto (usado si no está configurado en BD)
     */
    public record Password(
            @DefaultValue("10") int minLength
    ) {
    }
}
