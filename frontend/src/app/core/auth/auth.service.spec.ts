import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { errorInterceptor } from '../http/error.interceptor';
import { StoreContextService } from '../store-context/store-context.service';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { buildProblem, buildTokenResponse } from './auth.testing';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([errorInterceptor, authInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function login(permissions: readonly string[] = ['sales:pos:operate']): void {
    auth.login({ username: 'cajero1', password: 'secreta1' }).subscribe();
    http
      .expectOne('/api/v1/auth/login')
      .flush(buildTokenResponse({ accessToken: 'token-login', permissions }));
  }

  it('guarda el access token solo en memoria (ni localStorage ni sessionStorage)', () => {
    login();

    expect(auth.accessToken()).toBe('token-login');
    expect(auth.isAuthenticated()).toBe(true);
    for (const storage of [localStorage, sessionStorage]) {
      const dump = JSON.stringify(Object.entries(storage));
      expect(dump).not.toContain('token-login');
    }
  });

  it('login carga usuario, scope y mustChangePassword, y concilia el local activo', () => {
    auth.login({ username: 'u', password: 'p' }).subscribe();
    http
      .expectOne('/api/v1/auth/login')
      .flush(buildTokenResponse({ mustChangePassword: true, scope: 'POS' }));

    expect(auth.user()?.fullName).toBe('Cajero Uno');
    expect(auth.scope()).toBe('POS');
    expect(auth.mustChangePassword()).toBe(true);
    expect(TestBed.inject(StoreContextService).storeId()).toBe(10);
  });

  it('hasPermission, hasAnyPermission y hasAllPermissions evalúan los permisos efectivos', () => {
    login(['sales:pos:operate', 'cash:session:read']);

    expect(auth.hasPermission('sales:pos:operate')).toBe(true);
    expect(auth.hasPermission('security:user:read')).toBe(false);
    expect(auth.hasAnyPermission(['security:user:read', 'cash:session:read'])).toBe(true);
    expect(auth.hasAnyPermission(['security:user:read'])).toBe(false);
    expect(auth.hasAnyPermission([])).toBe(false);
    expect(auth.hasAllPermissions(['sales:pos:operate', 'cash:session:read'])).toBe(true);
    expect(auth.hasAllPermissions(['sales:pos:operate', 'security:user:read'])).toBe(false);
    expect(auth.hasAllPermissions([])).toBe(false);
  });

  it('sin sesión no hay permisos', () => {
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.hasPermission('sales:pos:operate')).toBe(false);
    expect(auth.hasAnyPermission(['sales:pos:operate'])).toBe(false);
  });

  it('logout limpia el estado y llama al servidor', () => {
    login();

    auth.logout().subscribe();
    const req = http.expectOne('/api/v1/auth/logout');
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBe(true);
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(auth.accessToken()).toBeNull();
    expect(auth.user()).toBeNull();
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.hasPermission('sales:pos:operate')).toBe(false);
    expect(TestBed.inject(StoreContextService).availableStores()).toEqual([]);
  });

  it('logout limpia el estado aunque el servidor falle', () => {
    login();
    let completed = false;

    auth.logout().subscribe({ complete: () => (completed = true) });
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 500, statusText: 'Error' });

    expect(completed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('refresh envía X-Requested-With, usa la cookie y actualiza el token', () => {
    let token = '';
    auth.refresh().subscribe((value) => (token = value));

    const req = http.expectOne('/api/v1/auth/refresh');
    expect(req.request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(buildTokenResponse({ accessToken: 'token-nuevo' }));

    expect(token).toBe('token-nuevo');
    expect(auth.accessToken()).toBe('token-nuevo');
  });

  it('refresh es single-flight: llamadas simultáneas comparten una sola petición', () => {
    const tokens: string[] = [];
    auth.refresh().subscribe((t) => tokens.push(t));
    auth.refresh().subscribe((t) => tokens.push(t));

    http.expectOne('/api/v1/auth/refresh').flush(buildTokenResponse({ accessToken: 'unico' }));

    expect(tokens).toEqual(['unico', 'unico']);
  });

  it('permite un refresh nuevo cuando el anterior ya terminó', () => {
    auth.refresh().subscribe();
    http.expectOne('/api/v1/auth/refresh').flush(buildTokenResponse({ accessToken: 'a' }));

    auth.refresh().subscribe();
    http.expectOne('/api/v1/auth/refresh').flush(buildTokenResponse({ accessToken: 'b' }));

    expect(auth.accessToken()).toBe('b');
  });

  it('si el refresh falla limpia la sesión y propaga el error', () => {
    login();
    let failed = false;

    auth.refresh().subscribe({ error: () => (failed = true) });
    http
      .expectOne('/api/v1/auth/refresh')
      .flush(buildProblem(401, 'TOKEN_INVALID'), { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('restoreSession recupera la sesión con la cookie de refresh', async () => {
    const restored = auth.restoreSession();
    http
      .expectOne('/api/v1/auth/refresh')
      .flush(buildTokenResponse({ accessToken: 'restaurado', permissions: ['cash:session:read'] }));
    await restored;

    expect(auth.accessToken()).toBe('restaurado');
    expect(auth.hasPermission('cash:session:read')).toBe(true);
  });

  it('restoreSession abandona la espera a los 8 s si el servidor no responde', async () => {
    vi.useFakeTimers();
    try {
      let settled = false;
      const restored = auth.restoreSession().then(() => (settled = true));
      expect(http.match('/api/v1/auth/refresh')).toHaveLength(1);

      await vi.advanceTimersByTimeAsync(7999);
      expect(settled).toBe(false);
      await vi.advanceTimersByTimeAsync(1);
      await restored;

      expect(settled).toBe(true);
      expect(auth.isAuthenticated()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('restoreSession no falla si no hay sesión previa', async () => {
    const restored = auth.restoreSession();
    http
      .expectOne('/api/v1/auth/refresh')
      .flush(buildProblem(401, 'TOKEN_INVALID'), { status: 401, statusText: 'Unauthorized' });

    await expect(restored).resolves.toBeUndefined();
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('loadMe actualiza el usuario sin alterar mustChangePassword', () => {
    auth.login({ username: 'u', password: 'p' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(buildTokenResponse({ mustChangePassword: true }));

    auth.loadMe().subscribe();
    const req = http.expectOne('/api/v1/auth/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-1');
    req.flush(buildTokenResponse({ permissions: ['cash:session:read'] }).user);

    expect(auth.hasPermission('cash:session:read')).toBe(true);
    expect(auth.mustChangePassword()).toBe(true);
  });

  it('markPasswordChanged baja la bandera de cambio obligatorio', () => {
    auth.login({ username: 'u', password: 'p' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(buildTokenResponse({ mustChangePassword: true }));

    auth.markPasswordChanged();

    expect(auth.mustChangePassword()).toBe(false);
  });

  it('pinLogin deja un token con scope POS', () => {
    auth.pinLogin({ username: 'cajero1', pin: '1234' }).subscribe();
    http.expectOne('/api/v1/auth/pin-login').flush(buildTokenResponse({ scope: 'POS' }));

    expect(auth.scope()).toBe('POS');
  });

  it('ignora el HttpClient no relacionado: no expone el token en peticiones ajenas', () => {
    login();

    TestBed.inject(HttpClient).get('https://terceros.example/api').subscribe();

    const req = http.expectOne('https://terceros.example/api');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });
});
