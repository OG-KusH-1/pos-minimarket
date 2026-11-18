package cl.minimarket.pos.shared.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Propiedades de {@link StoreContextFilter} (prefijo {@code pos.store-context}).
 *
 * <p>Los defaults viven en código (regla H.1: las tareas no editan {@code application.yml}); T95
 * puede consolidarlos allí.
 *
 * @param globalPaths patrones Ant (sin el contexto del servlet) de rutas que no exigen
 *        {@code X-Store-Id}, para endpoints que no pueden llevar {@link GlobalEndpoint}: públicos
 *        de autenticación, actuator, OpenAPI y {@code /error}. Reemplazar la lista completa en
 *        configuración si se agregan rutas.
 * @param allowAnonymous si es {@code true}, una request sin autenticación a un endpoint no global se
 *        deja pasar validando solo el formato de los headers (el local queda sin validar). Default
 *        {@code false} (falla cerrado con 401). Existe solo para pruebas y para el periodo previo a
 *        security (T21); no debe activarse en producción
 */
@ConfigurationProperties(prefix = "pos.store-context")
public record StoreContextProperties(
        @DefaultValue({
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/actuator/**",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/error"
        }) List<String> globalPaths,
        @DefaultValue("false") boolean allowAnonymous) {

    /** Copia defensiva e inmutable de la lista. */
    public StoreContextProperties {
        globalPaths = globalPaths == null ? List.of() : List.copyOf(globalPaths);
    }
}
