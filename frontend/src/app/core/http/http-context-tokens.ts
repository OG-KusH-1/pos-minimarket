import { HttpContext, HttpContextToken } from '@angular/common/http';

/**
 * Tokens de `HttpContext` con los que el código llamador configura el comportamiento de los
 * interceptores sin acoplarse a ellos. Se pasan en la opción `context` de `HttpClient`.
 */

/**
 * Marca la petición como [I] (API.md F.1): `idempotencyInterceptor` le agrega `Idempotency-Key`.
 * Úsese {@link withIdempotency} para crear el contexto.
 */
export const IDEMPOTENT = new HttpContextToken<boolean>(() => false);

/**
 * Clave de idempotencia de la petición. La asigna `idempotencyInterceptor` la primera vez y se
 * conserva en el contexto, de modo que cualquier reintento de la MISMA petición (operador `retry`,
 * reintento tras refrescar el token) reenvíe la misma clave. Un llamador puede fijarla de
 * antemano (p. ej. una clave persistida con el carrito para sobrevivir a un crash, ADR-25).
 */
export const IDEMPOTENCY_KEY = new HttpContextToken<string | null>(() => null);

/**
 * Evita que `errorInterceptor` muestre el snackbar para esa petición. El error igualmente se
 * convierte en `ApiError`. Para formularios que muestran el error por su cuenta.
 */
export const SKIP_ERROR_NOTIFICATION = new HttpContextToken<boolean>(() => false);

/**
 * Crea un contexto que marca la petición como idempotente.
 *
 * Debe crearse UNO por operación de negocio: reutilizar el mismo `HttpContext` en operaciones
 * distintas reutilizaría la misma clave y el servidor las trataría como la misma operación.
 *
 * @param context Contexto existente al que agregar la marca (opcional).
 */
export function withIdempotency(context: HttpContext = new HttpContext()): HttpContext {
  return context.set(IDEMPOTENT, true);
}

/** Crea un contexto que suprime el snackbar automático de errores. */
export function withoutErrorNotification(context: HttpContext = new HttpContext()): HttpContext {
  return context.set(SKIP_ERROR_NOTIFICATION, true);
}
