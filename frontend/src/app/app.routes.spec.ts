import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';
import { buildTokenResponse } from './core/auth/auth.testing';
import { NAV_ITEMS, requiredPermissions } from './core/layout/nav-items';
import { Login } from './features/auth/login/login';
import { Password } from './features/auth/password/password';
import { Dashboard } from './features/dashboard/dashboard';
import { Sale } from './features/pos/sale/sale';
import { Returns } from './features/pos/returns/returns';
import { TaxDocuments } from './features/pos/tax-documents/tax-documents';
import { Sessions } from './features/cash/sessions/sessions';
import { Products } from './features/catalog/products/products';
import { Categories } from './features/catalog/categories/categories';
import { Brands } from './features/catalog/brands/brands';
import { Taxes } from './features/catalog/taxes/taxes';
import { Scale } from './features/catalog/scale/scale';
import { Imports } from './features/catalog/imports/imports';
import { Promotions } from './features/promotions/promotions';
import { Customers } from './features/customers/customers';
import { Suppliers } from './features/purchasing/suppliers/suppliers';
import { Orders } from './features/purchasing/orders/orders';
import { Receipts } from './features/purchasing/receipts/receipts';
import { Replenishment } from './features/replenishment/replenishment';
import { Stock } from './features/inventory/stock/stock';
import { Kardex } from './features/inventory/kardex/kardex';
import { Adjustments } from './features/inventory/adjustments/adjustments';
import { Counts } from './features/inventory/counts/counts';
import { Transfers } from './features/inventory/transfers/transfers';
import { Lots } from './features/inventory/lots/lots';
import { Shrinkage } from './features/shrinkage/shrinkage';
import { Reports } from './features/reports/reports';
import { Company } from './features/settings/company/company';
import { Stores } from './features/settings/stores/stores';
import { Settings } from './features/settings/settings/settings';
import { Users } from './features/security/users/users';
import { Roles } from './features/security/roles/roles';
import { Audit } from './features/security/audit/audit';

/** Ruta placeholder esperada: URL, título en español y componente que debe renderizar. */
interface PlaceholderRoute {
  readonly url: string;
  readonly title: string;
  readonly component: Type<unknown>;
}

/**
 * Lista exacta de rutas de las sub-features de la sección H.1 del plan. Si se agrega una
 * sub-feature, debe añadirse aquí (y en `app.routes.ts` y `nav-items.ts`).
 */
const PLACEHOLDER_ROUTES: readonly PlaceholderRoute[] = [
  { url: '/login', title: 'Iniciar sesión', component: Login },
  { url: '/cambiar-clave', title: 'Cambiar contraseña', component: Password },
  { url: '/inicio', title: 'Inicio', component: Dashboard },
  { url: '/pos/venta', title: 'Venta', component: Sale },
  { url: '/pos/devoluciones', title: 'Devoluciones', component: Returns },
  { url: '/pos/documentos-tributarios', title: 'Documentos tributarios', component: TaxDocuments },
  { url: '/caja', title: 'Caja', component: Sessions },
  { url: '/catalogo/productos', title: 'Productos', component: Products },
  { url: '/catalogo/categorias', title: 'Categorías', component: Categories },
  { url: '/catalogo/marcas', title: 'Marcas', component: Brands },
  { url: '/catalogo/impuestos', title: 'Impuestos', component: Taxes },
  { url: '/catalogo/balanza', title: 'Códigos de balanza', component: Scale },
  { url: '/catalogo/importaciones', title: 'Importaciones', component: Imports },
  { url: '/promociones', title: 'Promociones', component: Promotions },
  { url: '/clientes', title: 'Clientes', component: Customers },
  { url: '/compras/proveedores', title: 'Proveedores', component: Suppliers },
  { url: '/compras/ordenes', title: 'Órdenes de compra', component: Orders },
  { url: '/compras/recepciones', title: 'Recepciones', component: Receipts },
  { url: '/reabastecimiento', title: 'Reabastecimiento', component: Replenishment },
  { url: '/inventario/stock', title: 'Stock', component: Stock },
  { url: '/inventario/kardex', title: 'Kardex', component: Kardex },
  { url: '/inventario/ajustes', title: 'Ajustes de inventario', component: Adjustments },
  { url: '/inventario/tomas', title: 'Tomas de inventario', component: Counts },
  { url: '/inventario/transferencias', title: 'Transferencias', component: Transfers },
  { url: '/inventario/lotes', title: 'Lotes y vencimientos', component: Lots },
  { url: '/mermas', title: 'Mermas', component: Shrinkage },
  { url: '/reportes', title: 'Reportes', component: Reports },
  { url: '/configuracion/empresa', title: 'Empresa', component: Company },
  { url: '/configuracion/locales', title: 'Locales y terminales', component: Stores },
  { url: '/configuracion/parametros', title: 'Parámetros', component: Settings },
  { url: '/seguridad/usuarios', title: 'Usuarios', component: Users },
  { url: '/seguridad/roles', title: 'Roles y permisos', component: Roles },
  { url: '/seguridad/auditoria', title: 'Auditoría', component: Audit },
];

/** Rutas públicas: se renderizan fuera del layout, por lo que no figuran en el menú. */
const PUBLIC_URLS = ['/login', '/cambiar-clave'];

/** Todos los permisos que habilitan algún ítem del menú (equivale a un administrador). */
const ALL_PERMISSIONS = NAV_ITEMS.flatMap((item) => requiredPermissions(item));

describe('app.routes', () => {
  const snackBarOpen = vi.fn();

  beforeEach(() => {
    localStorage.clear();
    snackBarOpen.mockReset();
    // El Shell inyecta `AuthService` (HttpClient), por lo que el test necesita un HttpClient de prueba.
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
      ],
    });
  });

  /** Simula una sesión iniciada con los permisos indicados (por defecto, todos). */
  function signIn(
    permissions: readonly string[] = ALL_PERMISSIONS,
    mustChangePassword = false,
  ): void {
    TestBed.inject(AuthService).login({ username: 'u', password: 'p' }).subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/login')
      .flush(buildTokenResponse({ permissions, mustChangePassword }));
  }

  it('cubre las 33 sub-features de H.1 sin URL repetidas', () => {
    expect(PLACEHOLDER_ROUTES).toHaveLength(33);
    expect(new Set(PLACEHOLDER_ROUTES.map((route) => route.url)).size).toBe(33);
  });

  it.each(PLACEHOLDER_ROUTES)('navega a $url y renderiza su componente', async (route) => {
    signIn();
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl(route.url);
    harness.detectChanges();

    // Las rutas de negocio cuelgan del Shell, por eso se busca el componente en todo el árbol.
    const rendered = harness.fixture.debugElement.query(By.directive(route.component));
    expect(rendered).not.toBeNull();
    expect(TestBed.inject(Router).url).toBe(route.url);
    expect((rendered.nativeElement as HTMLElement).querySelector('h1')?.textContent).toBe(
      route.title,
    );
  });

  it('redirige la raíz a /inicio', async () => {
    signIn();
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/');

    expect(TestBed.inject(Router).url).toBe('/inicio');
  });

  it('redirige las URL desconocidas a /inicio', async () => {
    signIn();
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/no-existe');

    expect(TestBed.inject(Router).url).toBe('/inicio');
  });

  it('todo ítem del menú apunta a una ruta existente', () => {
    const known = new Set(PLACEHOLDER_ROUTES.map((route) => route.url));
    for (const item of NAV_ITEMS) {
      expect(known, item.path).toContain(item.path);
    }
  });

  it('todas las rutas de negocio (no públicas) figuran en el menú con un permiso', () => {
    const menuPaths = NAV_ITEMS.map((item) => item.path).sort();
    const businessUrls = PLACEHOLDER_ROUTES.map((route) => route.url)
      .filter((url) => !PUBLIC_URLS.includes(url))
      .sort();

    expect(menuPaths).toEqual(businessUrls);
    for (const item of NAV_ITEMS) {
      // `permission` admite un código o una lista ("cualquiera de", p. ej. Reportes).
      for (const code of requiredPermissions(item)) {
        expect(code, item.path).toMatch(/^[a-z-]+:[a-z-]+:[a-z-]+$/);
      }
    }
  });

  describe('seguridad de navegación', () => {
    it('sin sesión redirige las rutas del layout a /login con returnUrl', async () => {
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/caja');

      expect(TestBed.inject(Router).url).toBe('/login?returnUrl=%2Fcaja');
    });

    it('sin sesión /login es accesible', async () => {
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/login');

      expect(TestBed.inject(Router).url).toBe('/login');
    });

    it('sin sesión /cambiar-clave redirige a /login', async () => {
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/cambiar-clave');

      expect(TestBed.inject(Router).url).toBe('/login?returnUrl=%2Fcambiar-clave');
    });

    it('sin el permiso de la ruta avisa con un snackbar y redirige al inicio del usuario', async () => {
      signIn(['report:dashboard:read']);
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/caja');

      expect(TestBed.inject(Router).url).toBe('/inicio');
      expect(snackBarOpen).toHaveBeenCalledOnce();
    });

    it('un cajero que entra a / va a /pos/venta (no queda en blanco)', async () => {
      signIn(['sales:pos:operate', 'cash:session:read']);
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/');

      expect(TestBed.inject(Router).url).toBe('/pos/venta');
    });

    it('un usuario sin módulos va a /sin-acceso y ve el mensaje, sin menú', async () => {
      signIn([]);
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/');
      harness.detectChanges();

      expect(TestBed.inject(Router).url).toBe('/sin-acceso');
      const root = harness.fixture.nativeElement as HTMLElement;
      expect(root.textContent).toContain('Tu usuario no tiene módulos asignados');
      expect(root.querySelectorAll('nav a[mat-list-item]')).toHaveLength(0);
    });

    it('/sin-acceso no exige permiso y no figura en el menú', async () => {
      signIn([]);
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/sin-acceso');

      expect(TestBed.inject(Router).url).toBe('/sin-acceso');
      expect(NAV_ITEMS.map((item) => item.path)).not.toContain('/sin-acceso');
    });

    it('con el permiso de la ruta permite el acceso, también a sus subrutas', async () => {
      signIn(['report:dashboard:read', 'cash:session:read']);
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/caja');

      expect(TestBed.inject(Router).url).toBe('/caja');
      expect(snackBarOpen).not.toHaveBeenCalled();
    });

    it('Reportes se abre con cualquiera de los permisos report:*:read', async () => {
      signIn(['report:dashboard:read', 'report:cash:read']);
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/reportes');

      expect(TestBed.inject(Router).url).toBe('/reportes');
    });

    it('con mustChangePassword redirige a /cambiar-clave y esa ruta no entra en bucle', async () => {
      signIn(ALL_PERMISSIONS, true);
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/caja');
      expect(TestBed.inject(Router).url).toBe('/cambiar-clave');

      await harness.navigateByUrl('/cambiar-clave');
      expect(TestBed.inject(Router).url).toBe('/cambiar-clave');
    });
  });
});
