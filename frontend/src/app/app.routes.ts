import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { homeRedirectGuard } from './core/auth/home-redirect.guard';
import { navPermissionGuard } from './core/auth/nav-permission.guard';
import { NoAccess } from './core/layout/no-access';
import { Shell } from './core/layout/shell';

/**
 * Tabla de rutas de la aplicación.
 *
 * Diseño:
 * - Todas las rutas de negocio se cargan de forma diferida (`loadChildren`) desde el archivo
 *   `<sub-feature>.routes.ts` de su carpeta en `features/`; cada tarea de frontend edita solo
 *   el de su sub-feature (reglas de colisión H.1 del plan).
 * - Las rutas públicas (inicio de sesión y cambio de contraseña) viven fuera del layout; el resto
 *   se anida bajo {@link Shell}, que aporta la barra superior y el menú lateral.
 * - Las URL están en español y deben coincidir con `core/layout/nav-items.ts`.
 *
 * Seguridad de navegación (solo UX; la autorización real es del backend):
 * - El Shell exige sesión (`authGuard`) y, para cada ruta hija, el permiso de su ítem de
 *   `nav-items.ts` (`navPermissionGuard`, "cualquiera de"). Las features no declaran guards.
 * - `/login` es pública. `/cambiar-clave` exige sesión; `authGuard` la deja pasar con
 *   `mustChangePassword` para no entrar en bucle.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadChildren: () => import('./features/auth/login/login.routes').then((m) => m.LOGIN_ROUTES),
  },
  {
    path: 'cambiar-clave',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/auth/password/password.routes').then((m) => m.PASSWORD_ROUTES),
  },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    canActivateChild: [navPermissionGuard],
    children: [
      // La raíz lleva al primer módulo permitido del usuario (o a /sin-acceso).
      { path: '', pathMatch: 'full', canActivate: [homeRedirectGuard], children: [] },
      // Fuera del menú y sin permiso: destino de quien no tiene ningún módulo.
      { path: 'sin-acceso', title: 'Sin acceso', component: NoAccess },
      {
        path: 'inicio',
        loadChildren: () =>
          import('./features/dashboard/dashboard.routes').then((m) => m.DASHBOARD_ROUTES),
      },
      {
        path: 'pos/venta',
        loadChildren: () => import('./features/pos/sale/sale.routes').then((m) => m.SALE_ROUTES),
      },
      {
        path: 'pos/devoluciones',
        loadChildren: () =>
          import('./features/pos/returns/returns.routes').then((m) => m.RETURNS_ROUTES),
      },
      {
        path: 'pos/documentos-tributarios',
        loadChildren: () =>
          import('./features/pos/tax-documents/tax-documents.routes').then(
            (m) => m.TAX_DOCUMENTS_ROUTES,
          ),
      },
      {
        path: 'caja',
        loadChildren: () =>
          import('./features/cash/sessions/sessions.routes').then((m) => m.SESSIONS_ROUTES),
      },
      {
        path: 'catalogo/productos',
        loadChildren: () =>
          import('./features/catalog/products/products.routes').then((m) => m.PRODUCTS_ROUTES),
      },
      {
        path: 'catalogo/categorias',
        loadChildren: () =>
          import('./features/catalog/categories/categories.routes').then(
            (m) => m.CATEGORIES_ROUTES,
          ),
      },
      {
        path: 'catalogo/marcas',
        loadChildren: () =>
          import('./features/catalog/brands/brands.routes').then((m) => m.BRANDS_ROUTES),
      },
      {
        path: 'catalogo/impuestos',
        loadChildren: () =>
          import('./features/catalog/taxes/taxes.routes').then((m) => m.TAXES_ROUTES),
      },
      {
        path: 'catalogo/balanza',
        loadChildren: () =>
          import('./features/catalog/scale/scale.routes').then((m) => m.SCALE_ROUTES),
      },
      {
        path: 'catalogo/importaciones',
        loadChildren: () =>
          import('./features/catalog/imports/imports.routes').then((m) => m.IMPORTS_ROUTES),
      },
      {
        path: 'promociones',
        loadChildren: () =>
          import('./features/promotions/promotions.routes').then((m) => m.PROMOTIONS_ROUTES),
      },
      {
        path: 'clientes',
        loadChildren: () =>
          import('./features/customers/customers.routes').then((m) => m.CUSTOMERS_ROUTES),
      },
      {
        path: 'compras/proveedores',
        loadChildren: () =>
          import('./features/purchasing/suppliers/suppliers.routes').then(
            (m) => m.SUPPLIERS_ROUTES,
          ),
      },
      {
        path: 'compras/ordenes',
        loadChildren: () =>
          import('./features/purchasing/orders/orders.routes').then((m) => m.ORDERS_ROUTES),
      },
      {
        path: 'compras/recepciones',
        loadChildren: () =>
          import('./features/purchasing/receipts/receipts.routes').then((m) => m.RECEIPTS_ROUTES),
      },
      {
        path: 'reabastecimiento',
        loadChildren: () =>
          import('./features/replenishment/replenishment.routes').then(
            (m) => m.REPLENISHMENT_ROUTES,
          ),
      },
      {
        path: 'inventario/stock',
        loadChildren: () =>
          import('./features/inventory/stock/stock.routes').then((m) => m.STOCK_ROUTES),
      },
      {
        path: 'inventario/kardex',
        loadChildren: () =>
          import('./features/inventory/kardex/kardex.routes').then((m) => m.KARDEX_ROUTES),
      },
      {
        path: 'inventario/ajustes',
        loadChildren: () =>
          import('./features/inventory/adjustments/adjustments.routes').then(
            (m) => m.ADJUSTMENTS_ROUTES,
          ),
      },
      {
        path: 'inventario/tomas',
        loadChildren: () =>
          import('./features/inventory/counts/counts.routes').then((m) => m.COUNTS_ROUTES),
      },
      {
        path: 'inventario/transferencias',
        loadChildren: () =>
          import('./features/inventory/transfers/transfers.routes').then((m) => m.TRANSFERS_ROUTES),
      },
      {
        path: 'inventario/lotes',
        loadChildren: () =>
          import('./features/inventory/lots/lots.routes').then((m) => m.LOTS_ROUTES),
      },
      {
        path: 'mermas',
        loadChildren: () =>
          import('./features/shrinkage/shrinkage.routes').then((m) => m.SHRINKAGE_ROUTES),
      },
      {
        path: 'reportes',
        loadChildren: () =>
          import('./features/reports/reports.routes').then((m) => m.REPORTS_ROUTES),
      },
      {
        path: 'configuracion/empresa',
        loadChildren: () =>
          import('./features/settings/company/company.routes').then((m) => m.COMPANY_ROUTES),
      },
      {
        path: 'configuracion/locales',
        loadChildren: () =>
          import('./features/settings/stores/stores.routes').then((m) => m.STORES_ROUTES),
      },
      {
        path: 'configuracion/parametros',
        loadChildren: () =>
          import('./features/settings/settings/settings.routes').then((m) => m.SETTINGS_ROUTES),
      },
      {
        path: 'seguridad/usuarios',
        loadChildren: () =>
          import('./features/security/users/users.routes').then((m) => m.USERS_ROUTES),
      },
      {
        path: 'seguridad/roles',
        loadChildren: () =>
          import('./features/security/roles/roles.routes').then((m) => m.ROLES_ROUTES),
      },
      {
        path: 'seguridad/auditoria',
        loadChildren: () =>
          import('./features/security/audit/audit.routes').then((m) => m.AUDIT_ROUTES),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
