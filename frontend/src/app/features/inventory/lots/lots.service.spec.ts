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
import { LotsService } from './lots.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('LotsService', () => {
  let service: LotsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    service = TestBed.inject(LotsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listLots consulta GET /api/v1/lots con filtros productId, status, withStock, expiringWithinDays y q', async () => {
    const promise = firstValueFrom(
      service.listLots({
        productId: 12,
        status: 'ACTIVE',
        withStock: true,
        expiringWithinDays: 15,
        q: 'L2026',
        page: 0,
        size: 20,
        sort: ['expiryDate,asc'],
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/lots');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('productId')).toBe('12');
    expect(req.request.params.get('status')).toBe('ACTIVE');
    expect(req.request.params.get('withStock')).toBe('true');
    expect(req.request.params.get('expiringWithinDays')).toBe('15');
    expect(req.request.params.get('q')).toBe('L2026');

    flushJson(req, {
      content: [{ id: 1, lotNumber: 'L2026-01', quantity: 10, version: 2 }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['expiryDate,asc'],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.content?.[0]?.lotNumber).toBe('L2026-01');
  });

  it('updateLot envía PUT /api/v1/lots/{id} y desempaqueta 409 CONCURRENT_MODIFICATION', async () => {
    const okPromise = firstValueFrom(
      service.updateLot(9, {
        lotNumber: 'L-NUEVO',
        expiryDate: '2026-12-30',
        reason: 'Corrección de etiqueta',
        version: 3,
      }),
    );

    const okReq = http.expectOne('/api/v1/lots/9');
    expect(okReq.request.method).toBe('PUT');
    expect(okReq.request.body).toEqual({
      lotNumber: 'L-NUEVO',
      expiryDate: '2026-12-30',
      reason: 'Corrección de etiqueta',
      version: 3,
    });
    flushJson(okReq, { id: 9, lotNumber: 'L-NUEVO', version: 4 });
    expect((await okPromise).version).toBe(4);

    const conflictPromise = firstValueFrom(
      service.updateLot(
        9,
        { lotNumber: 'L-OTRO', reason: 'Reintento obsoleto', version: 3 },
        withoutErrorNotification(),
      ),
    );
    const conflictReq = http.expectOne('/api/v1/lots/9');
    conflictReq.flush(
      new Blob(
        [
          JSON.stringify({
            code: 'CONCURRENT_MODIFICATION',
            detail: 'El lote fue modificado por otro usuario.',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 409, statusText: 'Conflict' },
    );

    const error = await conflictPromise.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).code).toBe('CONCURRENT_MODIFICATION');
  });

  it('listExpiryAlerts y suggestFefo llaman a /api/v1/expiry-alerts y /api/v1/lots/fefo', async () => {
    const alertsPromise = firstValueFrom(service.listExpiryAlerts({ days: 10, page: 0, size: 20 }));
    const alertsReq = http.expectOne((r) => r.url === '/api/v1/expiry-alerts');
    expect(alertsReq.request.method).toBe('GET');
    expect(alertsReq.request.params.get('days')).toBe('10');
    flushJson(alertsReq, {
      content: [{ lotId: 4, lotNumber: 'L-EXP', daysToExpiry: 5, quantity: 3, valuation: 3000 }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: [],
    });
    const alerts = await alertsPromise;
    expect(alerts.content?.[0]?.lotNumber).toBe('L-EXP');

    const fefoPromise = firstValueFrom(service.suggestFefo({ productId: 5, quantity: 8 }));
    const fefoReq = http.expectOne((r) => r.url === '/api/v1/lots/fefo');
    expect(fefoReq.request.method).toBe('GET');
    expect(fefoReq.request.params.get('productId')).toBe('5');
    expect(fefoReq.request.params.get('quantity')).toBe('8');
    flushJson(fefoReq, {
      productId: 5,
      requestedQty: 8,
      shortage: 2,
      allocations: [{ lotId: 1, lotNumber: 'L-01', expiryDate: '2026-12-01', quantity: 6 }],
    });
    const fefo = await fefoPromise;
    expect(fefo.shortage).toBe(2);
    expect(fefo.allocations).toHaveLength(1);
  });
});
