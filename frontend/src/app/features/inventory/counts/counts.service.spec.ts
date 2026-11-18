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
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { CountsService } from './counts.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('CountsService', () => {
  let service: CountsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(CountsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listCounts consulta GET /api/v1/inventory-counts con filtros, paginación y orden', async () => {
    const promise = firstValueFrom(
      service.listCounts({
        status: 'OPEN',
        countType: 'CYCLIC',
        page: 1,
        size: 10,
        sort: ['createdAt,desc'],
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/inventory-counts');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('OPEN');
    expect(req.request.params.get('countType')).toBe('CYCLIC');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.getAll('sort')).toEqual(['createdAt,desc']);

    flushJson(req, {
      content: [{ id: 12, number: 'TI-001-000012', countType: 'CYCLIC', status: 'OPEN' }],
      page: 1,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      sort: ['createdAt,desc'],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.content?.[0]?.number).toBe('TI-001-000012');
  });

  it('createCount envía POST /api/v1/inventory-counts con Idempotency-Key', async () => {
    const promise = firstValueFrom(
      service.createCount({
        countType: 'CYCLIC',
        categoryId: 3,
        blind: true,
        notes: 'Conteo mensual',
      }),
    );

    const req = http.expectOne('/api/v1/inventory-counts');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(req.request.body).toEqual({
      countType: 'CYCLIC',
      categoryId: 3,
      blind: true,
      notes: 'Conteo mensual',
    });

    flushJson(req, {
      id: 15,
      number: 'TI-001-000015',
      countType: 'CYCLIC',
      status: 'OPEN',
      blind: true,
      version: 0,
    });

    const created = await promise;
    expect(created.id).toBe(15);
    expect(created.blind).toBe(true);
  });

  it('ejecuta operaciones de detalle, líneas y ciclo de vida en sus rutas exactas', async () => {
    const getPromise = firstValueFrom(service.getCount(15));
    const getReq = http.expectOne('/api/v1/inventory-counts/15');
    expect(getReq.request.method).toBe('GET');
    flushJson(getReq, { id: 15, number: 'TI-001-000015', status: 'OPEN', version: 1 });
    expect((await getPromise).version).toBe(1);

    const linesPromise = firstValueFrom(
      service.listCountLines({ id: 15, counted: false, q: 'leche', page: 0, size: 100 }),
    );
    const linesReq = http.expectOne((r) => r.url === '/api/v1/inventory-counts/15/lines');
    expect(linesReq.request.method).toBe('GET');
    expect(linesReq.request.params.get('counted')).toBe('false');
    expect(linesReq.request.params.get('q')).toBe('leche');
    flushJson(linesReq, {
      content: [{ id: 201, productId: 42, sku: 'LEC-01', name: 'Leche 1L', countedQty: undefined }],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
    });
    expect((await linesPromise).content).toHaveLength(1);

    const recordPromise = firstValueFrom(
      service.recordCountLines(15, { lines: [{ lineId: 201, countedQty: 12 }] }),
    );
    const recordReq = http.expectOne('/api/v1/inventory-counts/15/lines');
    expect(recordReq.request.method).toBe('PUT');
    expect(recordReq.request.body).toEqual({ lines: [{ lineId: 201, countedQty: 12 }] });
    flushJson(recordReq, [{ id: 201, productId: 42, countedQty: 12 }]);
    expect((await recordPromise)[0]?.countedQty).toBe(12);

    const submitPromise = firstValueFrom(service.submitCount(15));
    const submitReq = http.expectOne('/api/v1/inventory-counts/15/submit');
    expect(submitReq.request.method).toBe('POST');
    flushJson(submitReq, { id: 15, status: 'SUBMITTED' });
    expect((await submitPromise).status).toBe('SUBMITTED');

    const approvePromise = firstValueFrom(service.approveCount(15));
    const approveReq = http.expectOne('/api/v1/inventory-counts/15/approve');
    expect(approveReq.request.method).toBe('POST');
    flushJson(approveReq, { id: 15, status: 'APPROVED' });
    expect((await approvePromise).status).toBe('APPROVED');

    const rejectPromise = firstValueFrom(service.rejectCount(15, { reason: 'Recontar pasillo 2' }));
    const rejectReq = http.expectOne('/api/v1/inventory-counts/15/reject');
    expect(rejectReq.request.method).toBe('POST');
    expect(rejectReq.request.body).toEqual({ reason: 'Recontar pasillo 2' });
    flushJson(rejectReq, { id: 15, status: 'OPEN', rejectionReason: 'Recontar pasillo 2' });
    expect((await rejectPromise).status).toBe('OPEN');

    const cancelPromise = firstValueFrom(service.cancelCount(15, { reason: 'Toma duplicada' }));
    const cancelReq = http.expectOne('/api/v1/inventory-counts/15/cancel');
    expect(cancelReq.request.method).toBe('POST');
    expect(cancelReq.request.body).toEqual({ reason: 'Toma duplicada' });
    cancelReq.flush('', { status: 204, statusText: 'No Content' });
    await cancelPromise;

    const catPromise = firstValueFrom(service.listCategories());
    const catReq = http.expectOne((r) => r.url === '/api/v1/categories');
    expect(catReq.request.params.get('tree')).toBe('true');
    flushJson(catReq, [{ id: 3, name: 'Lácteos', children: [] }]);
    expect(await catPromise).toHaveLength(1);

    const prodPromise = firstValueFrom(service.listProductsByCategory(3));
    const prodReq = http.expectOne((r) => r.url === '/api/v1/products');
    expect(prodReq.request.params.get('categoryId')).toBe('3');
    flushJson(prodReq, { content: [], totalElements: 4, page: 0, size: 5, totalPages: 1 });
    expect((await prodPromise).totalElements).toBe(4);
  });

  describe('con errorInterceptor y respuestas ProblemDetail', () => {
    beforeEach(() => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          provideApiConfiguration(''),
          provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn() } },
        ],
      });
      service = TestBed.inject(CountsService);
      http = TestBed.inject(HttpTestingController);
    });

    it('desempaqueta ProblemDetail Blob en 422 SEGREGATION_OF_DUTIES y 409 CONCURRENT_MODIFICATION', async () => {
      const sodPromise = firstValueFrom(service.approveCount(15, withoutErrorNotification()));
      const sodReq = http.expectOne('/api/v1/inventory-counts/15/approve');
      sodReq.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'SEGREGATION_OF_DUTIES',
              detail: 'Quien aprueba la toma no puede haber registrado conteos.',
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 422, statusText: 'Unprocessable Entity' },
      );

      const sodErr = await sodPromise.catch((e: unknown) => e);
      expect(sodErr).toBeInstanceOf(ApiError);
      expect((sodErr as ApiError).code).toBe('SEGREGATION_OF_DUTIES');
      expect((sodErr as ApiError).message).toContain('Quien aprueba la toma');

      const conflictPromise = firstValueFrom(
        service.recordCountLines(
          15,
          { lines: [{ lineId: 1, countedQty: 2 }] },
          withoutErrorNotification(),
        ),
      );
      const conflictReq = http.expectOne('/api/v1/inventory-counts/15/lines');
      conflictReq.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'CONCURRENT_MODIFICATION',
              detail: 'La toma fue modificada concurrentemente.',
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 409, statusText: 'Conflict' },
      );

      const conflictErr = await conflictPromise.catch((e: unknown) => e);
      expect(conflictErr).toBeInstanceOf(ApiError);
      expect((conflictErr as ApiError).status).toBe(409);
      expect((conflictErr as ApiError).code).toBe('CONCURRENT_MODIFICATION');
    });

    it('desempaqueta ProblemDetail en string JSON al cancelar toma', async () => {
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
                        code: 'INVALID_STATE_TRANSITION',
                        detail: 'No se puede cancelar una toma ya aprobada.',
                      }),
                      status: 409,
                      statusText: 'Conflict',
                      url: r.url,
                    }),
                ),
            ]),
          ),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn() } },
        ],
      });
      service = TestBed.inject(CountsService);
      http = TestBed.inject(HttpTestingController);

      const err = await firstValueFrom(
        service.cancelCount(15, { reason: 'Motivo válido' }, withoutErrorNotification()),
      ).catch((e: unknown) => e);
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).code).toBe('INVALID_STATE_TRANSITION');
      expect((err as ApiError).message).toBe('No se puede cancelar una toma ya aprobada.');
    });
  });
});
