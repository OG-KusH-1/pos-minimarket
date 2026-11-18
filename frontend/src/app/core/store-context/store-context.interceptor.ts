import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { isGlobalEndpoint } from '../http/api-url';
import { StoreContextService } from './store-context.service';

/**
 * Agrega el contexto operativo a las peticiones de la API (ADR-12, API.md F.1):
 * `X-Store-Id` con el local activo y `X-Terminal-Id` con la terminal activa.
 *
 * - Los endpoints globales [G] (lista en `GLOBAL_ENDPOINT_PATTERNS`) y las URL ajenas a la API
 *   pasan sin cabeceras.
 * - Cada header se envía solo si hay valor: sin local activo no se envía `X-Store-Id` y el
 *   servidor responde con el error correspondiente, que es más claro que enviar un valor falso.
 * - `X-Terminal-Id` se envía en todos los endpoints no globales cuando hay terminal, no solo en
 *   los [T]: el servidor la ignora donde no aplica y `overrides` la usa de forma opcional.
 * - Si la petición ya trae el header (p. ej. una pantalla de administración consulta otro
 *   local), se respeta el valor del llamador.
 */
export const storeContextInterceptor: HttpInterceptorFn = (req, next) => {
  if (isGlobalEndpoint(req.url, req.method)) {
    return next(req);
  }
  const context = inject(StoreContextService);
  const headers: Record<string, string> = {};

  const storeId = context.storeId();
  if (storeId !== null && !req.headers.has('X-Store-Id')) {
    headers['X-Store-Id'] = String(storeId);
  }
  const terminalId = context.terminalId();
  if (terminalId !== null && !req.headers.has('X-Terminal-Id')) {
    headers['X-Terminal-Id'] = String(terminalId);
  }
  return next(Object.keys(headers).length === 0 ? req : req.clone({ setHeaders: headers }));
};
