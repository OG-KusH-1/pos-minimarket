package cl.minimarket.pos.shared.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un endpoint como [T] (API.md F.1): exige el header {@code X-Terminal-Id}, además del
 * local. Sin él, {@link StoreContextFilter} responde 400 {@code VALIDATION_ERROR}. Que la terminal
 * pertenezca al local lo valida el {@link StoreAccessValidator}.
 *
 * <p>Los endpoints sin esta anotación aceptan {@code X-Terminal-Id} de forma opcional: si llega,
 * se valida igual y se deja en el {@link StoreContext}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresTerminal {
}
