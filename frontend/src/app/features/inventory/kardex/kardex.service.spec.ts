import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom, throwError } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { ApiError } from '../../../core/http/api-error';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { KardexService } from './kardex.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('KardexService', () => {
  let service: KardexService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(KardexService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listMovements consulta GET /api/v1/kardex con producto, fechas, tipo, lote y orden', async () => {
    const promise = firstValueFrom(
      service.listMovements({
        productId: 10,
        from: '2026-11-01T00:00:00Z',
        to: '2026-11-13T23:59:59Z',
        movementType: 'SALE',
        lotId: 5,
        page: 0,
        size: 20,
        sort: ['occurredAt,desc'],
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/kardex');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('productId')).toBe('10');
    expect(req.request.params.get('from')).toBe('2026-11-01T00:00:00Z');
    expect(req.request.params.get('to')).toBe('2026-11-13T23:59:59Z');
    expect(req.request.params.get('movementType')).toBe('SALE');
    expect(req.request.params.get('lotId')).toBe('5');
    expect(req.request.params.getAll('sort')).toEqual(['occurredAt,desc']);

    flushJson(req, {
      content: [
        {
          id: 101,
          productId: 10,
          movementType: 'SALE',
          quantityDelta: -3,
          balanceAfter: 17,
          unitCost: 1100,
          sourceType: 'SALE',
          sourceId: 55,
          occurredAt: '2026-11-12T15:30:00Z',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['occurredAt,desc'],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.content?.[0]?.movementType).toBe('SALE');
    expect(page.content?.[0]?.quantityDelta).toBe(-3);
  });

  it('searchProducts y getProduct consultan /api/v1/products', async () => {
    const searchPromise = firstValueFrom(service.searchProducts('leche'));
    const searchReq = http.expectOne((r) => r.url === '/api/v1/products');
    expect(searchReq.request.params.get('q')).toBe('leche');
    expect(searchReq.request.params.get('size')).toBe('20');
    flushJson(searchReq, {
      content: [{ id: 10, sku: 'LEC-01', name: 'Leche entera 1L', saleType: 'UNIT' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['name,asc'],
    });

    const items = await searchPromise;
    expect(items).toHaveLength(1);
    expect(items[0]?.sku).toBe('LEC-01');

    const getPromise = firstValueFrom(service.getProduct(10));
    const getReq = http.expectOne('/api/v1/products/10');
    flushJson(getReq, { id: 10, sku: 'LEC-01', name: 'Leche entera 1L', unitOfMeasure: 'UNIT' });
    const product = await getPromise;
    expect(product.id).toBe(10);
  });

  it('desempaqueta ApiError desde Blob o string JSON bajo errorInterceptor', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    service = TestBed.inject(KardexService);
    http = TestBed.inject(HttpTestingController);

    const blobErrPromise = firstValueFrom(
      service.listMovements({ productId: 99 }, withoutErrorNotification()),
    );
    const blobReq = http.expectOne((r) => r.url === '/api/v1/kardex');
    blobReq.flush(
      new Blob([JSON.stringify({ code: 'NOT_FOUND', detail: 'Producto no encontrado' })], {
        type: 'application/problem+json',
      }),
      { status: 404, statusText: 'Not Found' },
    );

    await expect(blobErrPromise).rejects.toMatchObject<Partial<ApiError>>({
      code: 'NOT_FOUND',
      status: 404,
      message: 'Producto no encontrado',
    });

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(
          withInterceptors([
            errorInterceptor,
            (req) =>
              throwError(
                () =>
                  new HttpErrorResponse({
                    error: JSON.stringify({
                      code: 'ACCESS_DENIED',
                      detail: 'Sin permiso de lectura',
                    }),
                    status: 403,
                    statusText: 'Forbidden',
                    url: req.url,
                  }),
              ),
          ]),
        ),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    service = TestBed.inject(KardexService);
    http = TestBed.inject(HttpTestingController);

    const strErrPromise = firstValueFrom(service.getProduct(99, withoutErrorNotification()));
    await expect(strErrPromise).rejects.toMatchObject<Partial<ApiError>>({
      code: 'ACCESS_DENIED',
      status: 403,
      message: 'Sin permiso de lectura',
    });
  });
});
