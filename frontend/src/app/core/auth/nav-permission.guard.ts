import { CanActivateChildFn } from '@angular/router';
import { NAV_ITEMS, NavItem, requiredPermissions } from '../layout/nav-items';
import { permissionGuard } from './permission.guard';

/**
 * Busca el ítem del menú que corresponde a una URL: el de ruta más específica (prefijo más
 * largo) que coincida por segmentos completos, ignorando query y fragmento.
 * `/catalogo/productos/5/editar` coincide con `/catalogo/productos`; `/cajas` NO coincide con `/caja`.
 *
 * @param url URL de destino (`RouterStateSnapshot.url`).
 * @returns El ítem, o `undefined` si la URL no corresponde a ninguno.
 */
export function findNavItem(url: string): NavItem | undefined {
  const path = url.split(/[?#]/, 1)[0] ?? '';
  return NAV_ITEMS.filter((item) => path === item.path || path.startsWith(`${item.path}/`)).reduce<
    NavItem | undefined
  >(
    (best, item) => (best === undefined || item.path.length > best.path.length ? item : best),
    undefined,
  );
}

/**
 * `canActivateChild` del layout: exige a cada ruta hija el permiso de su ítem de `NAV_ITEMS`
 * con semántica "cualquiera de" (la misma regla que oculta el ítem del menú).
 *
 * Decisión de diseño: reutilizar `NAV_ITEMS` como única fuente de verdad evita declarar guards
 * en cada `*.routes.ts` de `features/**` y garantiza que menú y protección de ruta no diverjan.
 * Una URL sin ítem (p. ej. una ruta nueva aún no agregada al menú) se permite: la autorización
 * real siempre la hace el backend (403 `ACCESS_DENIED`). Si no hay permiso, delega en
 * `permissionGuard` (snackbar y redirección a `/inicio`; sin sesión, al login).
 */
export const navPermissionGuard: CanActivateChildFn = (childRoute, state) => {
  const item = findNavItem(state.url);
  return item === undefined
    ? true
    : permissionGuard(requiredPermissions(item), 'any')(childRoute, state);
};
