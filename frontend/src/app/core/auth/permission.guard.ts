import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AuthService } from './auth.service';
import { loginRedirect } from './auth.guard';
import { homeUrlFor } from './home-redirect.guard';

/** Cómo combinar varios permisos: `any` = basta uno, `all` = se requieren todos. */
export type PermissionMode = 'any' | 'all';

/**
 * Fábrica de guards que exigen permisos (catálogo D.1).
 *
 * Ejemplo en una ruta: `canActivate: [permissionGuard('catalog:product:read')]` o
 * `permissionGuard(['report:sales:read', 'report:cash:read'], 'any')`.
 *
 * - Sin sesión: redirige al login (igual que `authGuard`), así que puede usarse solo.
 * - Sin el permiso: avisa con un snackbar y redirige a la pantalla de inicio del usuario (`homeUrlFor`:
 *   su primer módulo permitido o `/sin-acceso`). Si ya se intentaba entrar a ese destino se cancela
 *   la navegación (`false`) para no entrar en un bucle de redirecciones.
 *
 * Este guard solo mejora la experiencia: la autorización real siempre la hace el backend
 * (403 `ACCESS_DENIED`).
 *
 * @param codes Un permiso o una lista. Una lista vacía deniega siempre (fail-closed).
 * @param mode `'any'` (por defecto) o `'all'`.
 */
export function permissionGuard(
  codes: string | readonly string[],
  mode: PermissionMode = 'any',
): CanActivateFn {
  const required = typeof codes === 'string' ? [codes] : codes;

  return (_route, state) => {
    const auth = inject(AuthService);
    const router = inject(Router);

    if (!auth.isAuthenticated()) {
      return loginRedirect(router, state.url);
    }
    const allowed =
      mode === 'all' ? auth.hasAllPermissions(required) : auth.hasAnyPermission(required);
    if (allowed) {
      return true;
    }
    inject(MatSnackBar).open('No tienes permiso para acceder a esta sección.', 'Cerrar', {
      duration: 5000,
    });
    const home = homeUrlFor(auth);
    return state.url.split(/[?#]/, 1)[0] === home ? false : router.parseUrl(home);
  };
}
