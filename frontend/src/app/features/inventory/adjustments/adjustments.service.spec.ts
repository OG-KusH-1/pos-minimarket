import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { ApiError } from '../../../core/http/api-error';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { AdjustmentsService } from './adjustments.service';

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('AdjustmentsService', () => {
  let service: AdjustmentsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    service = TestBed.inject(AdjustmentsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listAdjustments consulta GET /api/v1/stock-adjustments con filtros y ordenamiento', async () => {
    const promise = firstValueFrom(
      service.listAdjustments({
        status: 'PENDING_APPROVAL',
        from: '2026-11-01',
        to: '2026-11-18',
        page: 0,
        size: 20,
        sort: ['createdAt,desc'],
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/stock-adjustments');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('PENDING_APPROVAL');
    expect(req.request.params.get('from')).toBe('2026-11-01');
    expect(req.request.params.get('to')).toBe('2026-11-18');
    expect(req.request.params.getAll('sort')).toEqual(['createdAt,desc']);

    flushJson(req, {
      content: [{ id: 1, number: 'L01-AJ-000001', status: 'PENDING_APPROVAL' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['createdAt,desc'],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.content?.[0]?.number).toBe('L01-AJ-000001');
  });

  it('createAdjustment envía POST /api/v1/stock-adjustments con Idempotency-Key', async () => {
    const promise = firstValueFrom(
      service.createAdjustment({
        reasonCode: 'COUNT_CORRECTION',
        notes: 'Diferencia encontrada en pasillo 2',
        lines: [{ productId: 10, quantityDelta: 3 }],
      }),
    );

    const req = http.expectOne('/api/v1/stock-adjustments');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toMatch(UUID_REGEX);
    expect(req.request.body).toEqual({
      reasonCode: 'COUNT_CORRECTION',
      notes: 'Diferencia encontrada en pasillo 2',
      lines: [{ productId: 10, quantityDelta: 3 }],
    });

    flushJson(req, { id: 5, number: 'L01-AJ-000005', status: 'PENDING_APPROVAL' });
    const created = await promise;
    expect(created.id).toBe(5);
  });

  it('getAdjustment, approveAdjustment y rejectAdjustment llaman a los endpoints exactos', async () => {
    const getPromise = firstValueFrom(service.getAdjustment(5));
    const getReq = http.expectOne('/api/v1/stock-adjustments/5');
    expect(getReq.request.method).toBe('GET');
    flushJson(getReq, { id: 5, number: 'L01-AJ-000005' });
    expect((await getPromise).id).toBe(5);

    const approvePromise = firstValueFrom(service.approveAdjustment(5, 'ovr-token-99'));
    const approveReq = http.expectOne('/api/v1/stock-adjustments/5/approve');
    expect(approveReq.request.method).toBe('POST');
    expect(approveReq.request.headers.get('X-Override-Token')).toBe('ovr-token-99');
    flushJson(approveReq, { id: 5, status: 'APPROVED' });
    expect((await approvePromise).status).toBe('APPROVED');

    const rejectPromise = firstValueFrom(
      service.rejectAdjustment(6, { reason: 'Conteo duplicado' }),
    );
    const rejectReq = http.expectOne('/api/v1/stock-adjustments/6/reject');
    expect(rejectReq.request.method).toBe('POST');
    expect(rejectReq.request.body).toEqual({ reason: 'Conteo duplicado' });
    flushJson(rejectReq, { id: 6, status: 'REJECTED' });
    expect((await rejectPromise).status).toBe('REJECTED');
  });

  it('desempaqueta ProblemDetail Blob en 422 OVERRIDE_REQUIRED preservando action y context', async () => {
    const promise = firstValueFrom(
      service.approveAdjustment(7, undefined, withoutErrorNotification()),
    );
    const req = http.expectOne('/api/v1/stock-adjustments/7/approve');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            code: 'OVERRIDE_REQUIRED',
            detail: 'Supera el límite de aprobación del supervisor.',
            action: 'STOCK_ADJUSTMENT_APPROVAL',
            context: { entityId: 7, amount: 85000 },
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    const error = await promise.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(422);
    expect((error as ApiError).code).toBe('OVERRIDE_REQUIRED');
    expect((error as ApiError).extensions).toEqual({
      action: 'STOCK_ADJUSTMENT_APPROVAL',
      context: { entityId: 7, amount: 85000 },
    });
  });

  it('searchProducts excluye PACK y listProductLots consulta GET /api/v1/lots', async () => {
    const prodPromise = firstValueFrom(service.searchProducts('bebida'));
    const prodReq = http.expectOne((r) => r.url === '/api/v1/products');
    expect(prodReq.request.params.get('q')).toBe('bebida');
    expect(prodReq.request.params.get('active')).toBe('true');
    flushJson(prodReq, {
      content: [
        { id: 1, sku: 'BEB-1', name: 'Bebida 1L', saleType: 'UNIT' },
        { id: 2, sku: 'PACK-6', name: 'Pack x6', saleType: 'PACK' },
      ],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
      sort: [],
    });
    const products = await prodPromise;
    expect(products).toHaveLength(1);
    expect(products[0]?.id).toBe(1);

    const lotsPromise = firstValueFrom(service.listProductLots(1, true));
    const lotsReq = http.expectOne((r) => r.url === '/api/v1/lots');
    expect(lotsReq.request.params.get('productId')).toBe('1');
    expect(lotsReq.request.params.get('withStock')).toBe('true');
    flushJson(lotsReq, {
      content: [{ id: 12, lotNumber: 'L-01', quantity: 10 }],
      page: 0,
      size: 200,
      totalElements: 1,
      totalPages: 1,
      sort: ['expiryDate,asc'],
    });
    const lots = await lotsPromise;
    expect(lots).toHaveLength(1);
    expect(lots[0]?.lotNumber).toBe('L-01');
  });
});
