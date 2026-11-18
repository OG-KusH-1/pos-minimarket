import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { buildTokenResponse } from '../auth/auth.testing';
import {
  NAV_GROUPS,
  NAV_ITEMS,
  REPORT_READ_PERMISSIONS,
  filterNavGroups,
  requiredPermissions,
} from './nav-items';
import { Shell } from './shell';

/** Todos los permisos que habilitan algún ítem del menú (equivale a un administrador). */
const ALL_PERMISSIONS = NAV_ITEMS.flatMap((item) => requiredPermissions(item));

describe('Shell', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [Shell],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  function signIn(permissions: readonly string[], fullName = 'Cajero Uno'): void {
    TestBed.inject(AuthService).login({ username: 'u', password: 'p' }).subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/login')
      .flush(buildTokenResponse({ permissions, user: { fullName } }));
  }

  async function render(): Promise<{ root: HTMLElement; whenStable: () => Promise<void> }> {
    const fixture = TestBed.createComponent(Shell);
    await fixture.whenStable();
    return {
      root: fixture.nativeElement as HTMLElement,
      whenStable: () => fixture.whenStable(),
    };
  }

  function menuLabels(root: HTMLElement): (string | undefined)[] {
    return Array.from(root.querySelectorAll('nav a[mat-list-item]')).map((a) =>
      a.querySelector('[matListItemTitle]')?.textContent?.trim(),
    );
  }

  it('sin sesión muestra los textos «sin sesión» / «sin seleccionar» y un menú vacío', async () => {
    const { root } = await render();

    const toolbar = root.querySelector('mat-toolbar');
    expect(toolbar?.textContent).toContain('POS Minimarket');
    expect(toolbar?.textContent).toContain('Local: sin seleccionar');
    expect(toolbar?.textContent).toContain('Usuario: sin sesión');
    expect(menuLabels(root)).toEqual([]);
  });

  it('con sesión muestra el nombre del usuario y del local activo', async () => {
    signIn(ALL_PERMISSIONS);

    const toolbar = (await render()).root.querySelector('mat-toolbar');

    expect(toolbar?.textContent).toContain('Usuario: Cajero Uno');
    expect(toolbar?.textContent).toContain('Local: Local Centro');
  });

  it('con todos los permisos dibuja un enlace por cada ítem de nav-items con su URL', async () => {
    signIn(ALL_PERMISSIONS);

    const { root } = await render();

    const links = Array.from(root.querySelectorAll('nav a[mat-list-item]'));
    expect(links.map((a) => a.getAttribute('href'))).toEqual(NAV_ITEMS.map((i) => i.path));
    expect(menuLabels(root)).toEqual(NAV_ITEMS.map((i) => i.label));
  });

  it('muestra solo los ítems permitidos por los permisos del usuario', async () => {
    signIn(['sales:pos:operate', 'cash:session:read']);

    const { root } = await render();

    expect(menuLabels(root)).toEqual(['Venta', 'Caja']);
  });

  it('el menú reacciona cuando cambian los permisos (computed)', async () => {
    signIn(['sales:pos:operate']);
    const { root, whenStable } = await render();
    expect(menuLabels(root)).toEqual(['Venta']);

    TestBed.inject(AuthService).loadMe().subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/me')
      .flush(buildTokenResponse({ permissions: ['sales:pos:operate', 'cash:session:read'] }).user);
    await whenStable();

    expect(menuLabels(root)).toEqual(['Venta', 'Caja']);
  });

  it('muestra encabezado de grupo solo en los grupos visibles con más de un ítem', async () => {
    signIn(ALL_PERMISSIONS);

    const headings = Array.from((await render()).root.querySelectorAll('.group-title')).map((h) =>
      h.textContent?.replace(/\s+/g, ' ').trim(),
    );

    const expected = NAV_GROUPS.filter((g) => g.items.length > 1).map(
      (g) => `${g.icon} ${g.label}`,
    );
    expect(headings).toEqual(expected);
  });

  it('el botón de menú alterna el panel lateral', async () => {
    const fixture = TestBed.createComponent(Shell);
    await fixture.whenStable();
    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      'button[mat-icon-button]',
    );

    expect(button?.getAttribute('aria-expanded')).toBe('true');
    button?.click();
    await fixture.whenStable();

    expect(button?.getAttribute('aria-expanded')).toBe('false');
  });

  it('muestra el botón de cerrar sesión solo autenticado y al pulsarlo cierra sesión y va a /login', async () => {
    const withoutSession = await render();
    expect(withoutSession.root.querySelector('button[aria-label="Cerrar sesión"]')).toBeNull();

    signIn(['sales:pos:operate']);
    const { root } = await render();
    const logoutButton = root.querySelector<HTMLButtonElement>(
      'button[aria-label="Cerrar sesión"]',
    );
    expect(logoutButton).not.toBeNull();

    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    logoutButton!.click();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/logout').flush(null);

    await vi.waitFor(() => {
      expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
      expect(navigateSpy).toHaveBeenCalledWith('/login');
    });
  });
});

describe('nav-items: filtrado', () => {
  const only =
    (...granted: string[]) =>
    (required: readonly string[]) =>
      required.some((code) => granted.includes(code));

  it('Reportes acepta cualquiera de los 7 permisos report:*:read', () => {
    expect(REPORT_READ_PERMISSIONS).toHaveLength(7);
    for (const code of REPORT_READ_PERMISSIONS) {
      const labels = filterNavGroups(NAV_GROUPS, only(code)).flatMap((g) =>
        g.items.map((i) => i.label),
      );
      // El permiso del dashboard habilita además «Inicio».
      expect(labels, code).toContain('Reportes');
    }
  });

  it('el permiso de exportación por sí solo no muestra Reportes', () => {
    const labels = filterNavGroups(NAV_GROUPS, only('report:export:execute')).flatMap((g) =>
      g.items.map((i) => i.label),
    );

    expect(labels).not.toContain('Reportes');
  });

  it('descarta los grupos que quedan sin ítems visibles', () => {
    const groups = filterNavGroups(NAV_GROUPS, only('cash:session:read'));

    expect(groups.map((g) => g.id)).toEqual(['cash']);
  });

  it('sin permisos el menú queda vacío', () => {
    expect(filterNavGroups(NAV_GROUPS, () => false)).toEqual([]);
  });

  it('requiredPermissions normaliza un código o una lista', () => {
    expect(requiredPermissions({ label: 'a', path: '/a', permission: 'x:y:z' })).toEqual(['x:y:z']);
    expect(requiredPermissions({ label: 'a', path: '/a', permission: ['a:b:c', 'd:e:f'] })).toEqual(
      ['a:b:c', 'd:e:f'],
    );
  });
});
