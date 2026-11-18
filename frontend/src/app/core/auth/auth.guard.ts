import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';

/** Ruta de inicio de sesión (pública, fuera del layout). */
export const LOGIN_URL = '/login';
/** Ruta de cambio de clave obligatorio (pública, fuera del layout). */
export const CHANGE_PASSWORD_URL = '/cambiar-clave';

/**
 * Crea el `UrlTree` hacia el login conservando la URL pedida en `returnUrl`, para que tras
 * autenticarse el usuario vuelva a donde iba.
 */
export function loginRedirect(router: Router, requestedUrl: string): UrlTree {
  return router.createUrlTree([LOGIN_URL], { queryParams: { returnUrl: requestedUrl } });
}

/**
 * Exige una sesión activa.
 *
 * - Sin sesión: redirige a `/login?returnUrl=…`.
 * - Con sesión y `mustChangePassword`: redirige a `/cambiar-clave` (única pantalla permitida
 *   hasta que cambie la clave). La propia `/cambiar-clave` pasa el guard para evitar un bucle.
 *
 * Es síncrono: la sesión se restaura antes de arrancar la app (`provideAppInitializer` →
 * `AuthService.restoreSession`), de modo que cuando se evalúa ya se sabe si hay sesión.
 * Se aplica a la ruta padre del layout (`canActivate`/`canActivateChild`).
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.isAuthenticated()) {
    return loginRedirect(router, state.url);
  }
  if (auth.mustChangePassword() && !state.url.startsWith(CHANGE_PASSWORD_URL)) {
    return router.createUrlTree([CHANGE_PASSWORD_URL]);
  }
  return true;
};
