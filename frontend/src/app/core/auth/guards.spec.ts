import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { authGuard } from './auth.guard';
import { homeRedirectGuard, homeUrlFor } from './home-redirect.guard';
import { AuthService } from './auth.service';
import { buildTokenResponse } from './auth.testing';
import { permissionGuard } from './permission.guard';

describe('guards', () => {
  const snackBarOpen = vi.fn();
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    snackBarOpen.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
      ],
    });
    router = TestBed.inject(Router);
  });

  function signIn(options: Parameters<typeof buildTokenResponse>[0] = {}): void {
    TestBed.inject(AuthService).login({ username: 'u', password: 'p' }).subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/login')
      .flush(buildTokenResponse(options));
  }

  function run(guard: CanActivateFn, url: string): boolean | UrlTree {
    const result = TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );
    return result as boolean | UrlTree;
  }

  describe('authGuard', () => {
    it('sin sesión redirige a /login con returnUrl', () => {
      const result = run(authGuard, '/caja');

      expect(result).toBeInstanceOf(UrlTree);
      expect(router.serializeUrl(result as UrlTree)).toBe('/login?returnUrl=%2Fcaja');
    });

    it('con sesión permite el paso', () => {
      signIn();

      expect(run(authGuard, '/caja')).toBe(true);
    });

    it('con mustChangePassword redirige a /cambiar-clave', () => {
      signIn({ mustChangePassword: true });

      const result = run(authGuard, '/caja');

      expect(router.serializeUrl(result as UrlTree)).toBe('/cambiar-clave');
    });

    it('con mustChangePassword deja entrar a /cambiar-clave (sin bucle)', () => {
      signIn({ mustChangePassword: true });

      expect(run(authGuard, '/cambiar-clave')).toBe(true);
    });
  });

  describe('permissionGuard', () => {
    it('sin sesión redirige a /login', () => {
      const result = run(permissionGuard('cash:session:read'), '/caja');

      expect(router.serializeUrl(result as UrlTree)).toBe('/login?returnUrl=%2Fcaja');
    });

    it('permite el paso con el permiso', () => {
      signIn({ permissions: ['cash:session:read'] });

      expect(run(permissionGuard('cash:session:read'), '/caja')).toBe(true);
      expect(snackBarOpen).not.toHaveBeenCalled();
    });

    it('sin el permiso avisa y redirige al primer módulo permitido del usuario', () => {
      signIn({ permissions: ['sales:pos:operate'] });

      const result = run(permissionGuard('cash:session:read'), '/caja');

      expect(router.serializeUrl(result as UrlTree)).toBe('/pos/venta');
      expect(snackBarOpen).toHaveBeenCalledOnce();
    });

    it('sin ningún módulo permitido redirige a /sin-acceso', () => {
      signIn();

      const result = run(permissionGuard('report:dashboard:read'), '/inicio');

      expect(router.serializeUrl(result as UrlTree)).toBe('/sin-acceso');
    });

    it('si el destino denegado ya es el inicio del usuario cancela la navegación (sin bucle)', () => {
      signIn({ permissions: ['cash:session:read'] });

      expect(run(permissionGuard('report:dashboard:read'), '/caja')).toBe(false);
    });

    it("modo 'any' (por defecto) basta con un permiso de la lista", () => {
      signIn({ permissions: ['report:cash:read'] });

      expect(run(permissionGuard(['report:sales:read', 'report:cash:read']), '/reportes')).toBe(
        true,
      );
    });

    it("modo 'all' exige todos los permisos", () => {
      signIn({ permissions: ['sales:pos:operate', 'report:cash:read'] });
      const guard = permissionGuard(['report:sales:read', 'report:cash:read'], 'all');

      expect(run(guard, '/reportes')).toBeInstanceOf(UrlTree);

      TestBed.inject(AuthService).loadMe().subscribe();
      TestBed.inject(HttpTestingController)
        .expectOne('/api/v1/auth/me')
        .flush(
          buildTokenResponse({
            permissions: ['sales:pos:operate', 'report:sales:read', 'report:cash:read'],
          }).user,
        );

      expect(run(guard, '/reportes')).toBe(true);
    });

    it('una lista vacía deniega siempre (fail-closed)', () => {
      signIn({ permissions: ['sales:pos:operate'] });

      expect(run(permissionGuard([]), '/reportes')).toBeInstanceOf(UrlTree);
    });
  });

  describe('homeRedirectGuard', () => {
    it('lleva a cada usuario a su primer módulo permitido, en el orden del menú', () => {
      signIn({ permissions: ['cash:session:read', 'sales:pos:operate'] });

      const result = run(homeRedirectGuard, '/');

      expect(router.serializeUrl(result as UrlTree)).toBe('/pos/venta');
      expect(homeUrlFor(TestBed.inject(AuthService))).toBe('/pos/venta');
    });

    it('un usuario con el dashboard va a /inicio', () => {
      signIn({ permissions: ['report:dashboard:read', 'sales:pos:operate'] });

      expect(router.serializeUrl(run(homeRedirectGuard, '/') as UrlTree)).toBe('/inicio');
    });

    it('un usuario sin ningún módulo va a /sin-acceso', () => {
      signIn();

      expect(router.serializeUrl(run(homeRedirectGuard, '/') as UrlTree)).toBe('/sin-acceso');
    });
  });
});
