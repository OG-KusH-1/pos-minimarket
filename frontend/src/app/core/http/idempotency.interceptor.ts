import { HttpInterceptorFn } from '@angular/common/http';
import { IDEMPOTENCY_KEY, IDEMPOTENT } from './http-context-tokens';
import { generateUuid } from './uuid';

/**
 * Agrega `Idempotency-Key` (UUID) a las peticiones [I] (ADR-14: venta, devolución, movimiento
 * de caja, abono, autorización de tarjeta) para que un reintento no duplique dinero ni stock.
 *
 * La petición se marca con `withIdempotency()` (token `IDEMPOTENT`). La clave se genera la
 * primera vez y se guarda en el `HttpContext` de la petición (token `IDEMPOTENCY_KEY`): como el
 * contexto pertenece a la petición original, los reintentos de la MISMA petición (operador
 * `retry`, reintento tras refrescar el token) reenvían la misma clave y el servidor puede
 * deduplicar. Una operación distinta crea su propio contexto y, por tanto, una clave nueva.
 *
 * Si el llamador ya fijó el header `Idempotency-Key` se respeta tal cual.
 */
export const idempotencyInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.context.get(IDEMPOTENT) || req.headers.has('Idempotency-Key')) {
    return next(req);
  }
  let key = req.context.get(IDEMPOTENCY_KEY);
  if (key === null) {
    key = generateUuid();
    req.context.set(IDEMPOTENCY_KEY, key);
  }
  return next(req.clone({ setHeaders: { 'Idempotency-Key': key } }));
};
