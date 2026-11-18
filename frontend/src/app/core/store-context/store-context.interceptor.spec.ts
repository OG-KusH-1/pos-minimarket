import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { storeContextInterceptor } from './store-context.interceptor';
import { StoreContextService } from './store-context.service';

describe('storeContextInterceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let context: StoreContextService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([storeContextInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    context = TestBed.inject(StoreContextService);
    context.setStore(10);
    context.setTerminal(3);
  });

  afterEach(() => http.verify());

  function headersOf(url: string, method = 'GET') {
    client.request(method, url).subscribe();
    const req = http.expectOne(url);
    req.flush(null);
    return req.request.headers;
  }

  it.each([
    '/api/v1/sales',
    '/api/v1/cash-sessions/current',
    '/api/v1/stock?page=0',
    '/api/v1/pos/cart/quote',
  ])('agrega X-Store-Id y X-Terminal-Id a %s', (url) => {
    const headers = headersOf(url);

    expect(headers.get('X-Store-Id')).toBe('10');
    expect(headers.get('X-Terminal-Id')).toBe('3');
  });

  /** Endpoints SIN [G] en F.2 (método, URL): deben recibir los headers. */
  const LOCAL_SCOPED: [string, string][] = [
    ['POST', '/api/v1/auth/pin-login'],
    ['POST', '/api/v1/auth/overrides'],
    ['POST', '/api/v1/auth/session/terminal'],
    ['POST', '/api/v1/stores/10/terminals'],
    ['PUT', '/api/v1/terminals/3'],
    ['POST', '/api/v1/terminals/3/deactivate'],
    ['GET', '/api/v1/settings'],
    ['GET', '/api/v1/settings/effective'],
    ['PUT', '/api/v1/settings/pos.x'],
    ['POST', '/api/v1/files'],
    ['GET', '/api/v1/files/3/content'],
    ['GET', '/api/v1/customers/5/account'],
    ['POST', '/api/v1/customers/5/payments'],
    ['POST', '/api/v1/customers/5/adjustments'],
    ['GET', '/api/v1/pos/products/search'],
    ['GET', '/api/v1/pos/promotions/active'],
    ['POST', '/api/v1/pos/cart/quote'],
    ['POST', '/api/v1/sales'],
    ['GET', '/api/v1/cash-sessions/current'],
    ['GET', '/api/v1/stock'],
    ['GET', '/api/v1/purchase-orders'],
    ['GET', '/api/v1/reports/sales'],
    ['GET', '/api/v1/kardex'],
    ['POST', '/api/v1/stock-transfers'],
    ['GET', '/api/v1/shrinkages'],
    ['GET', '/api/v1/replenishment/runs'],
  ];

  /** Endpoints marcados [G] en F.2 (método, URL): no reciben headers. */
  const GLOBAL: [string, string][] = [
    ['POST', '/api/v1/auth/login'],
    ['POST', '/api/v1/auth/refresh'],
    ['POST', '/api/v1/auth/logout'],
    ['GET', '/api/v1/auth/me'],
    ['GET', '/api/v1/auth/me/stores'],
    ['POST', '/api/v1/auth/me/password'],
    ['PUT', '/api/v1/auth/me/pin'],
    ['GET', '/api/v1/users'],
    ['POST', '/api/v1/users'],
    ['PUT', '/api/v1/users/5'],
    ['POST', '/api/v1/users/5/disable'],
    ['POST', '/api/v1/users/5/reset-pin'],
    ['GET', '/api/v1/users/5/sessions'],
    ['DELETE', '/api/v1/users/5/sessions'],
    ['GET', '/api/v1/roles'],
    ['PUT', '/api/v1/roles/4/permissions'],
    ['GET', '/api/v1/permissions'],
    ['PUT', '/api/v1/roles/4/limits'],
    ['GET', '/api/v1/audit-log'],
    ['POST', '/api/v1/audit-log/verify'],
    ['GET', '/api/v1/company'],
    ['PUT', '/api/v1/company'],
    ['GET', '/api/v1/stores'],
    ['GET', '/api/v1/stores/10'],
    ['POST', '/api/v1/stores'],
    ['PUT', '/api/v1/stores/10'],
    ['POST', '/api/v1/stores/10/deactivate'],
    ['POST', '/api/v1/stores/10/activate'],
    ['GET', '/api/v1/stores/10/terminals'],
    ['GET', '/api/v1/categories?tree=true'],
    ['POST', '/api/v1/categories/3/deactivate'],
    ['GET', '/api/v1/brands'],
    ['PUT', '/api/v1/brands/2'],
    ['GET', '/api/v1/taxes'],
    ['GET', '/api/v1/products?q=leche'],
    ['PUT', '/api/v1/products/5/price'],
    ['PUT', '/api/v1/products/5/taxes'],
    ['GET', '/api/v1/products/5/suppliers'],
    ['GET', '/api/v1/products/export'],
    ['DELETE', '/api/v1/products/5/barcodes/9'],
    ['GET', '/api/v1/scale-barcode-configs'],
    ['POST', '/api/v1/catalog/imports'],
    ['POST', '/api/v1/catalog/imports/3/confirm'],
    ['GET', '/api/v1/catalog/imports/templates/PRODUCTS'],
    ['GET', '/api/v1/suppliers'],
    ['GET', '/api/v1/suppliers/2/products'],
    ['POST', '/api/v1/product-suppliers'],
    ['PUT', '/api/v1/product-suppliers/3/preferred'],
    ['GET', '/api/v1/promotions'],
    ['POST', '/api/v1/promotions/4/activate'],
    ['GET', '/api/v1/customers'],
    ['GET', '/api/v1/customers/5'],
    ['POST', '/api/v1/customers'],
    ['PUT', '/api/v1/customers/5'],
    ['POST', '/api/v1/customers/5/deactivate'],
    ['PUT', '/api/v1/customers/5/credit'],
  ];

  it.each(LOCAL_SCOPED)('%s %s (sin [G]) recibe X-Store-Id', (method, url) => {
    expect(headersOf(url, method).get('X-Store-Id')).toBe('10');
  });

  it.each(GLOBAL)('%s %s (endpoint [G]) NO recibe headers', (method, url) => {
    const headers = headersOf(url, method);

    expect(headers.has('X-Store-Id')).toBe(false);
    expect(headers.has('X-Terminal-Id')).toBe(false);
  });

  it('no agrega headers a URL fuera de la API', () => {
    const headers = headersOf('https://terceros.example/api/v1/sales');

    expect(headers.has('X-Store-Id')).toBe(false);
  });

  it('omite cada header cuando no hay valor activo', () => {
    context.setStore(null);
    context.setTerminal(null);

    const headers = headersOf('/api/v1/sales');

    expect(headers.has('X-Store-Id')).toBe(false);
    expect(headers.has('X-Terminal-Id')).toBe(false);
  });

  it('solo envía X-Store-Id si no hay terminal', () => {
    context.setTerminal(null);

    const headers = headersOf('/api/v1/sales');

    expect(headers.get('X-Store-Id')).toBe('10');
    expect(headers.has('X-Terminal-Id')).toBe(false);
  });

  it('respeta un X-Store-Id fijado por el llamador', () => {
    client.get('/api/v1/stock', { headers: { 'X-Store-Id': '20' } }).subscribe();

    const req = http.expectOne('/api/v1/stock');
    expect(req.request.headers.get('X-Store-Id')).toBe('20');
    req.flush(null);
  });
});
