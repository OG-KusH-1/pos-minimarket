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
import { StockService } from './stock.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('StockService', () => {
  let service: StockService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(StockService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listStock consulta GET /api/v1/stock con filtros, paginación y orden', async () => {
    const promise = firstValueFrom(
      service.listStock({
        q: 'arroz',
        categoryId: 4,
        status: 'BELOW_MIN',
        lotControlled: true,
        page: 1,
        size: 10,
        sort: ['quantity,asc'],
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/stock');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('arroz');
    expect(req.request.params.get('categoryId')).toBe('4');
    expect(req.request.params.get('status')).toBe('BELOW_MIN');
    expect(req.request.params.get('lotControlled')).toBe('true');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.getAll('sort')).toEqual(['quantity,asc']);

    flushJson(req, {
      content: [
        {
          productId: 10,
          sku: 'ARR-01',
          name: 'Arroz grado 1',
          categoryName: 'Abarrotes',
          unitOfMeasure: 'UNIT',
          quantity: 4,
          minQty: 10,
          maxQty: 40,
          status: 'BELOW_MIN',
          valuation: 4800,
        },
      ],
      page: 1,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      sort: ['quantity,asc'],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.content?.[0]?.status).toBe('BELOW_MIN');
  });

  it('getAlerts, getStockByStore, updateLimits y listCategories llaman a sus endpoints', async () => {
    const alertsPromise = firstValueFrom(service.getAlerts());
    const alertsReq = http.expectOne('/api/v1/stock/alerts');
    expect(alertsReq.request.method).toBe('GET');
    flushJson(alertsReq, { belowMin: [], zeroCount: 2, expiringCount: 1, expiring: [] });
    const alerts = await alertsPromise;
    expect(alerts.zeroCount).toBe(2);

    const byStorePromise = firstValueFrom(service.getStockByStore(15));
    const byStoreReq = http.expectOne('/api/v1/stock/15');
    expect(byStoreReq.request.method).toBe('GET');
    flushJson(byStoreReq, {
      productId: 15,
      stores: [{ storeId: 1, storeName: 'Local 1', quantity: 5, minQty: 2, maxQty: 20 }],
      inTransit: 3,
      companyQuantity: 8,
    });
    const byStore = await byStorePromise;
    expect(byStore.companyQuantity).toBe(8);
    expect(byStore.inTransit).toBe(3);

    const limitsPromise = firstValueFrom(service.updateLimits(15, { minQty: 5, maxQty: 25 }));
    const limitsReq = http.expectOne('/api/v1/stock/15/limits');
    expect(limitsReq.request.method).toBe('PUT');
    expect(limitsReq.request.body).toEqual({ minQty: 5, maxQty: 25 });
    flushJson(limitsReq, {
      productId: 15,
      minQty: 5,
      maxQty: 25,
      quantity: 5,
      status: 'BELOW_MIN',
    });
    const updated = await limitsPromise;
    expect(updated.minQty).toBe(5);

    const catPromise = firstValueFrom(service.listCategories());
    const catReq = http.expectOne((r) => r.url === '/api/v1/categories');
    expect(catReq.request.params.get('tree')).toBe('true');
    flushJson(catReq, [{ id: 1, name: 'Abarrotes', children: [] }]);
    const categories = await catPromise;
    expect(categories).toHaveLength(1);
  });

  describe('con errorInterceptor y respuestas Blob o string JSON', () => {
    beforeEach(() => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          provideApiConfiguration(''),
          provideHttpClient(withInterceptors([errorInterceptor])),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn() } },
        ],
      });
      service = TestBed.inject(StockService);
      http = TestBed.inject(HttpTestingController);
    });

    it('desempaqueta ProblemDetail Blob en 400 VALIDATION_ERROR al actualizar límites', async () => {
      const promise = firstValueFrom(
        service.updateLimits(10, { minQty: 10, maxQty: 5 }, withoutErrorNotification()),
      );
      const req = http.expectOne('/api/v1/stock/10/limits');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'VALIDATION_ERROR',
              detail: 'Los límites de stock son inválidos.',
              errors: [
                {
                  field: 'maxQty',
                  code: 'MIN_MAX_ORDER',
                  message: 'El stock máximo no puede ser menor que el stock mínimo.',
                },
              ],
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 400, statusText: 'Bad Request' },
      );

      await expect(promise).rejects.toMatchObject({
        name: 'ApiError',
        status: 400,
        code: 'VALIDATION_ERROR',
        hasFieldErrors: true,
        errors: [
          {
            field: 'maxQty',
            code: 'MIN_MAX_ORDER',
            message: 'El stock máximo no puede ser menor que el stock mínimo.',
          },
        ],
      });
    });

    it('desempaqueta ProblemDetail Blob en 403 STORE_ACCESS_DENIED y string JSON', async () => {
      const promise = firstValueFrom(service.listStock({}, withoutErrorNotification()));
      const req = http.expectOne((r) => r.url === '/api/v1/stock');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'STORE_ACCESS_DENIED',
              detail: 'No tienes acceso al local seleccionado.',
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 403, statusText: 'Forbidden' },
      );

      const error = await promise.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).code).toBe('STORE_ACCESS_DENIED');
      expect((error as ApiError).message).toBe('No tienes acceso al local seleccionado.');

      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          provideApiConfiguration(''),
          provideHttpClient(
            withInterceptors([
              errorInterceptor,
              (r) =>
                throwError(
                  () =>
                    new HttpErrorResponse({
                      error: JSON.stringify({
                        code: 'STORE_ACCESS_DENIED',
                        detail: 'Sin acceso al local.',
                      }),
                      status: 403,
                      statusText: 'Forbidden',
                      url: r.url,
                    }),
                ),
            ]),
          ),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn() } },
        ],
      });
      service = TestBed.inject(StockService);
      http = TestBed.inject(HttpTestingController);

      const strError = await firstValueFrom(
        service.listStock({}, withoutErrorNotification()),
      ).catch((e: unknown) => e);
      expect(strError).toBeInstanceOf(ApiError);
      expect((strError as ApiError).code).toBe('STORE_ACCESS_DENIED');
      expect((strError as ApiError).message).toBe('Sin acceso al local.');
    });
  });
});
