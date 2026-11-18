package cl.minimarket.pos.security.auth.refresh;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades de la cookie de refresh token ({@code pos.security.refresh-cookie}).
 *
 * @param name nombre de la cookie (por defecto {@code pos_refresh})
 * @param path ruta de envío de la cookie (por defecto {@code /api/v1/auth})
 * @param secure si la cookie exige HTTPS ({@code true} en producción, {@code false} en dev/test)
 */
@ConfigurationProperties(prefix = "pos.security.refresh-cookie")
public record RefreshCookieProperties(
        @DefaultValue("pos_refresh") String name,
        @DefaultValue("/api/v1/auth") String path,
        @DefaultValue("true") boolean secure
) {
}
