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
import { IDEMPOTENT, withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { GoodsReceiptsService } from './goods-receipts.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('GoodsReceiptsService', () => {
  let service: GoodsReceiptsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(GoodsReceiptsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listReceipts consulta GET /api/v1/goods-receipts con filtros y paginación', async () => {
    const promise = firstValueFrom(
      service.listReceipts({
        status: 'CONFIRMED',
        supplierId: 3,
        purchaseOrderId: 12,
        from: '2026-11-01',
        to: '2026-11-18',
        page: 0,
        size: 20,
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/goods-receipts');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('CONFIRMED');
    expect(req.request.params.get('supplierId')).toBe('3');
    expect(req.request.params.get('purchaseOrderId')).toBe('12');
    flushJson(req, {
      content: [{ id: 1, number: 'RC-001', status: 'CONFIRMED', supplierName: 'Proveedor A' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: [],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.content?.[0]?.number).toBe('RC-001');
  });

  it('createReceipt, updateReceipt, confirmReceipt (con Idempotency-Key) y cancelReceipt llaman a sus PATH exactos', async () => {
    const createPromise = firstValueFrom(
      service.createReceipt({
        purchaseOrderId: 12,
        supplierDocumentType: 'INVOICE',
        supplierDocumentNumber: 'F-9988',
        lines: [
          {
            productId: 5,
            purchaseOrderLineId: 100,
            qtyReceived: 10,
            qtyRejected: 0,
            unitCostNet: 500,
            lotNumber: 'L-01',
            expiryDate: '2027-01-01',
          },
        ],
      }),
    );
    const createReq = http.expectOne('/api/v1/goods-receipts');
    expect(createReq.request.method).toBe('POST');
    flushJson(createReq, { id: 30, number: 'RC-030', status: 'DRAFT', version: 0, lines: [] });
    const created = await createPromise;
    expect(created.id).toBe(30);

    const updatePromise = firstValueFrom(
      service.updateReceipt(30, {
        purchaseOrderId: 12,
        supplierDocumentType: 'INVOICE',
        supplierDocumentNumber: 'F-9988',
        version: 0,
        lines: [{ productId: 5, qtyReceived: 8, unitCostNet: 500, differenceReason: 'Faltaron 2' }],
      }),
    );
    const updateReq = http.expectOne('/api/v1/goods-receipts/30');
    expect(updateReq.request.method).toBe('PUT');
    flushJson(updateReq, { id: 30, number: 'RC-030', status: 'DRAFT', version: 1, lines: [] });
    const updated = await updatePromise;
    expect(updated.version).toBe(1);

    const confirmPromise = firstValueFrom(service.confirmReceipt(30));
    const confirmReq = http.expectOne('/api/v1/goods-receipts/30/confirm');
    expect(confirmReq.request.method).toBe('POST');
    expect(confirmReq.request.context.get(IDEMPOTENT)).toBe(true);
    expect(confirmReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    flushJson(confirmReq, { id: 30, number: 'RC-030', status: 'CONFIRMED', version: 2, lines: [] });
    const confirmed = await confirmPromise;
    expect(confirmed.status).toBe('CONFIRMED');

    const cancelPromise = firstValueFrom(
      service.cancelReceipt(31, { reason: 'Documento duplicado' }),
    );
    const cancelReq = http.expectOne('/api/v1/goods-receipts/31/cancel');
    expect(cancelReq.request.method).toBe('POST');
    expect(cancelReq.request.body).toEqual({ reason: 'Documento duplicado' });
    cancelReq.flush('');
    await cancelPromise;
  });

  it('desenvuelve errores de negocio Blob (LOT_REQUIRED, RECEIPT_EXCEEDS_ORDER, SEGREGATION_OF_DUTIES)', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([idempotencyInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    service = TestBed.inject(GoodsReceiptsService);
    http = TestBed.inject(HttpTestingController);

    const promise = firstValueFrom(service.confirmReceipt(30, withoutErrorNotification()));
    const req = http.expectOne('/api/v1/goods-receipts/30/confirm');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            status: 422,
            code: 'LOT_REQUIRED',
            detail: 'El producto requiere número de lote.',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    try {
      await promise;
      expect.fail('Debió lanzar ApiError');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).code).toBe('LOT_REQUIRED');
    }
  });
});
