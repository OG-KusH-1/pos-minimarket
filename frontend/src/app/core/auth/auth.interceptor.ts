import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { ErrorCodes } from '../http/api-error';
import { isApiUrl, isAuthUrl, isPublicAuthUrl } from '../http/api-url';
import { AuthService } from './auth.service';

/**
 * Autentica las peticiones a la API y recupera la sesión cuando el access token vence.
 *
 * Comportamiento:
 * 1. URL que no es de la API propia: pasa sin tocar (nunca se envía el token a terceros).
 * 2. `/api/v1/auth/*`: se agrega `withCredentials` para que viaje la cookie de refresh
 *    (ADR-20); solo a esas rutas, que es donde la cookie tiene `Path`.
 * 3. `login` y `refresh` son públicos: no llevan Bearer y un 401 suyo no dispara otro refresh
 *    (evita bucles).
 * 4. El resto lleva `Authorization: Bearer <token>`. Si responde 401 se llama a
 *    {@link AuthService.refresh} (single-flight: N peticiones simultáneas comparten un único
 *    refresh) y se reintenta UNA vez con el token nuevo. Si el reintento vuelve a fallar, su
 *    error se propaga tal cual (no hay segundo ciclo). Si el refresh falla, la sesión queda
 *    limpia, se redirige a `/login` y se propaga el 401 original.
 *
 * Si al llegar el 401 el token en memoria ya es distinto del enviado (otro refresh terminó entre
 * tanto), se reintenta con el token actual SIN refrescar de nuevo.
 *
 * Excepción: un 401 con `INVALID_CREDENTIALS` (clave actual errónea al cambiar contraseña, PIN
 * errado…) significa que el usuario se equivocó, no que el token venciera, así que no se
 * refresca.
 *
 * Nota sobre el reintento: los interceptores registrados DESPUÉS de este (`store-context` e
 * `idempotency`) se ejecutan de nuevo sobre la petición reintentada; la clave de idempotencia se
 * conserva porque vive en el `HttpContext` de la petición original.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isApiUrl(req.url)) {
    return next(req);
  }
  const auth = inject(AuthService);
  const router = inject(Router);

  const base = isAuthUrl(req.url) ? req.clone({ withCredentials: true }) : req;
  if (isPublicAuthUrl(req.url)) {
    return next(base);
  }

  const sentToken = auth.accessToken();
  return next(withBearer(base, sentToken)).pipe(
    catchError((error: unknown) => {
      if (!isRecoverableUnauthorized(error)) {
        return throwError(() => error);
      }
      // Otra petición ya refrescó mientras esta estaba en vuelo: basta reintentar con el token
      // vigente. Un segundo refresh gastaría el refresh token rotado sin necesidad.
      const current = auth.accessToken();
      if (current !== null && current !== sentToken) {
        return next(withBearer(base, current));
      }
      return auth.refresh().pipe(
        catchError(() => {
          redirectToLogin(router);
          return throwError(() => error);
        }),
        switchMap((token) => next(withBearer(base, token))),
      );
    }),
  );
};

/** Agrega el header Bearer si hay token; sin token envía la petición tal cual. */
function withBearer<T>(req: HttpRequest<T>, token: string | null): HttpRequest<T> {
  return token === null ? req : req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

/** 401 que puede deberse a un access token vencido (y por tanto vale la pena refrescar). */
function isRecoverableUnauthorized(error: unknown): error is HttpErrorResponse {
  if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
    return false;
  }
  const code = (error.error as { code?: unknown } | null)?.code;
  return code !== ErrorCodes.invalidCredentials;
}

/** Lleva al usuario a `/login` recordando dónde estaba, salvo que ya esté allí. */
function redirectToLogin(router: Router): void {
  if (router.url.startsWith('/login')) {
    return;
  }
  void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
}
