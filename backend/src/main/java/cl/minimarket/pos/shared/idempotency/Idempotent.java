package cl.minimarket.pos.shared.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un método de controlador como idempotente ([I] en API.md F.1, ADR-14): exige el header
 * {@code Idempotency-Key} (UUID) y garantiza que un reintento con la misma clave no vuelva a
 * ejecutar el caso de uso.
 *
 * <p>Lo procesa {@link IdempotencyFilter}; el controlador no debe hacer nada más. Comportamiento:
 * <ul>
 *   <li>Sin header, o con un valor que no es UUID: 400 {@code VALIDATION_ERROR}.</li>
 *   <li>Clave nueva: se ejecuta el endpoint. Si responde 2xx, se guarda estado y cuerpo.</li>
 *   <li>Misma clave y mismo request ya completado: se devuelve la respuesta guardada, sin ejecutar
 *       el endpoint, con el header {@code Idempotent-Replayed: true}.</li>
 *   <li>Misma clave con el primero aún en curso: 409 {@code IDEMPOTENCY_IN_PROGRESS}.</li>
 *   <li>Misma clave con otro request (distinto cuerpo, ruta o query): 409 {@code IDEMPOTENCY_CONFLICT}.</li>
 * </ul>
 *
 * <p>Restricciones del endpoint anotado: debe estar autenticado (la clave es por usuario) y debe
 * responder JSON o sin cuerpo, porque la respuesta se guarda en una columna {@code jsonb}. Las
 * respuestas de error (no 2xx) no se guardan: la clave se libera para que el cliente pueda
 * corregir el problema y reintentar con la misma clave.
 *
 * <p>Puede ir en el método o en la clase del controlador (aplica a todos sus métodos).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Idempotent {
}
