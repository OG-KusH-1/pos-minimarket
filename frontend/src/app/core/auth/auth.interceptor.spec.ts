import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { idempotencyInterceptor } from '../http/idempotency.interceptor';
import { withIdempotency } from '../http/http-context-tokens';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { buildProblem, buildTokenResponse } from './auth.testing';

const UNAUTHORIZED = { status: 401, statusText: 'Unauthorized' };

describe('authInterceptor', () => {
  let auth: AuthService;
  let client: HttpClient;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(AuthService);
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    auth.login({ username: 'u', password: 'p' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(buildTokenResponse({ accessToken: 'viejo' }));
  });

  afterEach(() => http.verify());

  it('agrega Authorization: Bearer a las peticiones de la API', () => {
    client.get('/api/v1/products').subscribe();

    const req = http.expectOne('/api/v1/products');
    expect(req.request.headers.get('Authorization')).toBe('Bearer viejo');
    expect(req.request.withCredentials).toBe(false);
    req.flush([]);
  });

  it('no envía el token ni credenciales a URL fuera de la API', () => {
    client.get('https://terceros.example/api/v1/products').subscribe();
    client.get('/assets/logo.svg').subscribe();

    for (const url of ['https://terceros.example/api/v1/products', '/assets/logo.svg']) {
      const req = http.expectOne(url);
      expect(req.request.headers.has('Authorization')).toBe(false);
      expect(req.request.withCredentials).toBe(false);
      req.flush({});
    }
  });

  it('agrega withCredentials solo a /api/v1/auth/*', () => {
    client.get('/api/v1/auth/me').subscribe();

    const req = http.expectOne('/api/v1/auth/me');
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.headers.get('Authorization')).toBe('Bearer viejo');
    req.flush({});
  });

  it('no intercepta login: sin Bearer y un 401 no dispara refresh', () => {
    let failed = false;
    client.post('/api/v1/auth/login', {}).subscribe({ error: () => (failed = true) });

    const req = http.expectOne('/api/v1/auth/login');
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(req.request.withCredentials).toBe(true);
    req.flush(buildProblem(401, 'INVALID_CREDENTIALS'), UNAUTHORIZED);

    expect(failed).toBe(true);
    http.expectNone('/api/v1/auth/refresh');
  });

  it('ante un 401 hace UN refresh y reintenta con el token nuevo', () => {
    let body: unknown;
    client.get('/api/v1/products').subscribe((value) => (body = value));

    http.expectOne('/api/v1/products').flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);
    http.expectOne('/api/v1/auth/refresh').flush(buildTokenResponse({ accessToken: 'nuevo' }));
    const retry = http.expectOne('/api/v1/products');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer nuevo');
    retry.flush(['ok']);

    expect(body).toEqual(['ok']);
    expect(auth.accessToken()).toBe('nuevo');
  });

  it('con dos 401 simultáneos hace un solo refresh y reintenta ambas peticiones', () => {
    const results: unknown[] = [];
    client.get('/api/v1/products').subscribe((v) => results.push(['products', v]));
    client.get('/api/v1/sales').subscribe((v) => results.push(['sales', v]));

    http.expectOne('/api/v1/products').flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);
    http.expectOne('/api/v1/sales').flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);

    // Un único refresh para ambas (expectOne falla si hubiera dos).
    http.expectOne('/api/v1/auth/refresh').flush(buildTokenResponse({ accessToken: 'nuevo' }));

    for (const url of ['/api/v1/products', '/api/v1/sales']) {
      const retry = http.expectOne(url);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer nuevo');
      retry.flush(url);
    }
    expect(results).toEqual([
      ['products', '/api/v1/products'],
      ['sales', '/api/v1/sales'],
    ]);
  });

  it('un segundo 401 tras el reintento se propaga sin otro ciclo de refresh', () => {
    let status = 0;
    client.get('/api/v1/products').subscribe({ error: (e) => (status = e.status) });

    http.expectOne('/api/v1/products').flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);
    http.expectOne('/api/v1/auth/refresh').flush(buildTokenResponse({ accessToken: 'nuevo' }));
    http.expectOne('/api/v1/products').flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);

    expect(status).toBe(401);
    http.expectNone('/api/v1/auth/refresh');
  });

  it('si el refresh falla limpia la sesión y propaga el 401 original', () => {
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    let status = 0;
    client.get('/api/v1/products').subscribe({ error: (e) => (status = e.status) });

    http.expectOne('/api/v1/products').flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);
    http.expectOne('/api/v1/auth/refresh').flush(buildProblem(401, 'TOKEN_INVALID'), UNAUTHORIZED);

    expect(status).toBe(401);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('redirige al login conservando la URL de origen', () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    client.get('/api/v1/products').subscribe({ error: () => undefined });

    http.expectOne('/api/v1/products').flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);
    http.expectOne('/api/v1/auth/refresh').flush(buildProblem(401, 'TOKEN_INVALID'), UNAUTHORIZED);

    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: router.url } });
  });

  it('un 401 INVALID_CREDENTIALS (clave actual errónea) no dispara refresh', () => {
    let code = '';
    client.post('/api/v1/auth/me/password', {}).subscribe({ error: (e) => (code = e.error.code) });

    http
      .expectOne('/api/v1/auth/me/password')
      .flush(buildProblem(401, 'INVALID_CREDENTIALS'), UNAUTHORIZED);

    expect(code).toBe('INVALID_CREDENTIALS');
    http.expectNone('/api/v1/auth/refresh');
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('un 403 no dispara refresh', () => {
    client.get('/api/v1/users').subscribe({ error: () => undefined });

    http
      .expectOne('/api/v1/users')
      .flush(buildProblem(403, 'ACCESS_DENIED'), { status: 403, statusText: 'Forbidden' });

    http.expectNone('/api/v1/auth/refresh');
  });

  it('conserva la Idempotency-Key al reintentar tras el refresh', () => {
    client.post('/api/v1/sales', {}, { context: withIdempotency() }).subscribe();

    const first = http.expectOne('/api/v1/sales');
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    first.flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);
    http.expectOne('/api/v1/auth/refresh').flush(buildTokenResponse({ accessToken: 'nuevo' }));

    const retry = http.expectOne('/api/v1/sales');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush({});
  });

  it('si otro refresh ya terminó, reintenta con el token actual sin refrescar de nuevo', () => {
    const results: string[] = [];
    client.get('/api/v1/products').subscribe((v) => results.push('products:' + v));
    client.get('/api/v1/sales').subscribe((v) => results.push('sales:' + v));
    const products = http.expectOne('/api/v1/products');
    const sales = http.expectOne('/api/v1/sales');
    expect(sales.request.headers.get('Authorization')).toBe('Bearer viejo');

    // La primera petición dispara y completa el refresh antes de que llegue el 401 de la segunda.
    products.flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);
    http.expectOne('/api/v1/auth/refresh').flush(buildTokenResponse({ accessToken: 'nuevo' }));
    http.expectOne('/api/v1/products').flush('ok');
    sales.flush(buildProblem(401, 'UNAUTHENTICATED'), UNAUTHORIZED);

    http.expectNone('/api/v1/auth/refresh');
    const retry = http.expectOne('/api/v1/sales');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer nuevo');
    retry.flush('ok');
    expect(results).toEqual(['products:ok', 'sales:ok']);
  });
});
