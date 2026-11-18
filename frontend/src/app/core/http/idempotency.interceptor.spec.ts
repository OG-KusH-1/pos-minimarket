import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { retry } from 'rxjs';
import { IDEMPOTENCY_KEY, withIdempotency } from './http-context-tokens';
import { idempotencyInterceptor } from './idempotency.interceptor';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('idempotencyInterceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('agrega un UUID v4 en Idempotency-Key a las peticiones marcadas', () => {
    client.post('/api/v1/sales', {}, { context: withIdempotency() }).subscribe();

    const req = http.expectOne('/api/v1/sales');
    expect(req.request.headers.get('Idempotency-Key')).toMatch(UUID);
    req.flush({});
  });

  it('no agrega el header a las peticiones no marcadas', () => {
    client.post('/api/v1/products', {}).subscribe();

    const req = http.expectOne('/api/v1/products');
    expect(req.request.headers.has('Idempotency-Key')).toBe(false);
    req.flush({});
  });

  it('conserva la misma clave cuando la misma petición se reintenta', () => {
    client.post('/api/v1/sales', {}, { context: withIdempotency() }).pipe(retry(1)).subscribe();

    const first = http.expectOne('/api/v1/sales');
    const key = first.request.headers.get('Idempotency-Key');
    first.error(new ProgressEvent('error'));
    const second = http.expectOne('/api/v1/sales');

    expect(second.request.headers.get('Idempotency-Key')).toBe(key);
    second.flush({});
  });

  it('usa claves distintas para operaciones distintas', () => {
    client.post('/api/v1/sales', {}, { context: withIdempotency() }).subscribe();
    client.post('/api/v1/sales', {}, { context: withIdempotency() }).subscribe();

    const keys = http.match('/api/v1/sales').map((req) => {
      req.flush({});
      return req.request.headers.get('Idempotency-Key');
    });

    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it('respeta una clave fijada de antemano en el contexto', () => {
    const context = withIdempotency(new HttpContext().set(IDEMPOTENCY_KEY, 'clave-persistida'));

    client.post('/api/v1/sales', {}, { context }).subscribe();

    const req = http.expectOne('/api/v1/sales');
    expect(req.request.headers.get('Idempotency-Key')).toBe('clave-persistida');
    req.flush({});
  });

  it('respeta un header Idempotency-Key explícito', () => {
    client
      .post(
        '/api/v1/sales',
        {},
        { context: withIdempotency(), headers: { 'Idempotency-Key': 'manual' } },
      )
      .subscribe();

    const req = http.expectOne('/api/v1/sales');
    expect(req.request.headers.get('Idempotency-Key')).toBe('manual');
    req.flush({});
  });
});
