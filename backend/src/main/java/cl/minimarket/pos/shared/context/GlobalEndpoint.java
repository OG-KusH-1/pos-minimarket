package cl.minimarket.pos.shared.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un endpoint (método) o todos los de un controlador (clase) como <b>global</b> ([G] en
 * API.md F.1): no pertenece a un local y por tanto {@link StoreContextFilter} no exige
 * {@code X-Store-Id} ni deja un {@link StoreContext}.
 *
 * <p>Es la forma preferida de marcar endpoints globales, porque queda junto al código del
 * controlador. Para rutas que no tienen controlador propio (actuator, OpenAPI, {@code /error}) o
 * que se atienden antes de autenticar (login, refresh), el filtro además admite una lista de
 * patrones configurable ({@code pos.store-context.global-paths}).
 *
 * <p>Si el cliente envía igualmente {@code X-Store-Id} a un endpoint global, se ignora.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface GlobalEndpoint {
}
