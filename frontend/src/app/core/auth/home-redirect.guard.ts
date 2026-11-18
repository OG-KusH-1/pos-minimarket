import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { NAV_ITEMS, requiredPermissions } from '../layout/nav-items';
import { AuthService } from './auth.service';

/** Pantalla para usuarios sin ningún módulo asignado (dentro del layout, fuera del menú). */
export const NO_ACCESS_URL = '/sin-acceso';

/**
 * Calcula la pantalla de inicio de un usuario: la URL del primer ítem de `NAV_ITEMS` (en el
 * orden del menú) para el que tiene permiso, o {@link NO_ACCESS_URL} si no tiene ninguno.
 *
 * Así un cajero entra en `/pos/venta` y un bodeguero en su módulo, sin depender de que tengan
 * el permiso del dashboard. Es la única fuente del "destino por defecto": la usan
 * `homeRedirectGuard` y `permissionGuard` (destino tras un acceso denegado).
 *
 * @param auth Servicio de sesión con los permisos efectivos.
 */
export function homeUrlFor(auth: AuthService): string {
  const item = NAV_ITEMS.find((candidate) => auth.hasAnyPermission(requiredPermissions(candidate)));
  return item?.path ?? NO_ACCESS_URL;
}

/**
 * Guard de la ruta raíz del layout (`''`): redirige a {@link homeUrlFor}. Se declara como
 * `{ path: '', pathMatch: 'full', canActivate: [homeRedirectGuard], children: [] }`; nunca activa
 * la ruta, solo devuelve el `UrlTree` de destino.
 */
export const homeRedirectGuard: CanActivateFn = () => {
  return inject(Router).parseUrl(homeUrlFor(inject(AuthService)));
};
