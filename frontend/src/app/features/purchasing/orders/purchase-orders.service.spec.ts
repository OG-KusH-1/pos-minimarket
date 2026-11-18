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
import { PurchaseOrdersService } from './purchase-orders.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('PurchaseOrdersService', () => {
  let service: PurchaseOrdersService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PurchaseOrdersService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listOrders consulta GET /api/v1/purchase-orders con filtros y paginación', async () => {
    const promise = firstValueFrom(
      service.listOrders({
        q: 'OC-001',
        status: 'DRAFT',
        origin: 'MANUAL',
        supplierId: 5,
        from: '2026-11-01',
        to: '2026-11-18',
        page: 0,
        size: 20,
        sort: ['createdAt,desc'],
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/purchase-orders');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('OC-001');
    expect(req.request.params.get('status')).toBe('DRAFT');
    expect(req.request.params.get('origin')).toBe('MANUAL');
    expect(req.request.params.get('supplierId')).toBe('5');
    expect(req.request.params.get('from')).toBe('2026-11-01');
    expect(req.request.params.get('to')).toBe('2026-11-18');
    flushJson(req, {
      content: [{ id: 1, number: 'OC-001', status: 'DRAFT', supplierName: 'Proveedor A' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['createdAt,desc'],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.content?.[0]?.number).toBe('OC-001');
  });

  it('createOrder, updateOrder, sendOrder, cancelOrder y getReceiptDraft llaman a los PATH exactos', async () => {
    const createPromise = firstValueFrom(
      service.createOrder({
        supplierId: 5,
        expectedDate: '2026-11-25',
        lines: [{ productId: 10, qtyPurchaseUnits: 3, unitCostNet: 500 }],
      }),
    );
    const createReq = http.expectOne('/api/v1/purchase-orders');
    expect(createReq.request.method).toBe('POST');
    flushJson(createReq, { id: 15, number: 'OC-015', status: 'DRAFT', version: 0, lines: [] });
    const created = await createPromise;
    expect(created.id).toBe(15);

    const updatePromise = firstValueFrom(
      service.updateOrder(15, {
        supplierId: 5,
        version: 0,
        lines: [{ productId: 10, qtyPurchaseUnits: 4 }],
      }),
    );
    const updateReq = http.expectOne('/api/v1/purchase-orders/15');
    expect(updateReq.request.method).toBe('PUT');
    expect(updateReq.request.body).toEqual({
      supplierId: 5,
      version: 0,
      lines: [{ productId: 10, qtyPurchaseUnits: 4 }],
    });
    flushJson(updateReq, { id: 15, number: 'OC-015', status: 'DRAFT', version: 1, lines: [] });
    const updated = await updatePromise;
    expect(updated.version).toBe(1);

    const sendPromise = firstValueFrom(
      service.sendOrder(15, { emailToSupplier: true, version: 1 }),
    );
    const sendReq = http.expectOne('/api/v1/purchase-orders/15/send');
    expect(sendReq.request.method).toBe('POST');
    expect(sendReq.request.body).toEqual({ emailToSupplier: true, version: 1 });
    flushJson(sendReq, { id: 15, number: 'OC-015', status: 'SENT', version: 2, lines: [] });
    const sent = await sendPromise;
    expect(sent.status).toBe('SENT');

    const draftPromise = firstValueFrom(service.getReceiptDraft(15));
    const draftReq = http.expectOne('/api/v1/purchase-orders/15/receipt-draft');
    expect(draftReq.request.method).toBe('GET');
    flushJson(draftReq, {
      purchaseOrderId: 15,
      supplierId: 5,
      supplierDocumentType: 'INVOICE',
      lines: [{ productId: 10, purchaseOrderLineId: 101, qtyReceived: 4, unitCostNet: 500 }],
    });
    const draft = await draftPromise;
    expect(draft.purchaseOrderId).toBe(15);

    const cancelPromise = firstValueFrom(
      service.cancelOrder(15, { reason: 'Proveedor sin stock' }),
    );
    const cancelReq = http.expectOne('/api/v1/purchase-orders/15/cancel');
    expect(cancelReq.request.method).toBe('POST');
    expect(cancelReq.request.body).toEqual({ reason: 'Proveedor sin stock' });
    flushJson(cancelReq, { id: 15, number: 'OC-015', status: 'CANCELLED', version: 3, lines: [] });
    const cancelled = await cancelPromise;
    expect(cancelled.status).toBe('CANCELLED');
  });

  it('getOrderPdf retorna un Blob PDF desde GET /api/v1/purchase-orders/{id}/pdf', async () => {
    const pdfPromise = firstValueFrom(service.getOrderPdf(22));
    const req = http.expectOne('/api/v1/purchase-orders/22/pdf');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['%PDF-1.4'], { type: 'application/pdf' }));

    const blob = await pdfPromise;
    expect(blob).toBeInstanceOf(Blob);
    expect(blob.size).toBeGreaterThan(0);
  });

  it('desenvuelve ProblemDetail Blob en ApiError con code CONCURRENT_MODIFICATION', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    service = TestBed.inject(PurchaseOrdersService);
    http = TestBed.inject(HttpTestingController);

    const promise = firstValueFrom(
      service.updateOrder(
        9,
        { supplierId: 1, version: 0, lines: [{ productId: 1, qtyPurchaseUnits: 1 }] },
        withoutErrorNotification(),
      ),
    );
    const req = http.expectOne('/api/v1/purchase-orders/9');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            status: 409,
            code: 'CONCURRENT_MODIFICATION',
            detail: 'La orden fue modificada por otro usuario.',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 409, statusText: 'Conflict' },
    );

    await expect(promise).rejects.toBeInstanceOf(ApiError);
  });
});
