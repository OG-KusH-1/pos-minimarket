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
import { IDEMPOTENT, withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { TransfersService } from './transfers.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('TransfersService', () => {
  let service: TransfersService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(TransfersService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listTransfers consulta GET /api/v1/stock-transfers con filtros, paginación y orden', async () => {
    const promise = firstValueFrom(
      service.listTransfers({
        direction: 'out',
        status: 'SENT',
        from: '2026-11-01',
        to: '2026-11-18',
        page: 1,
        size: 10,
        sort: ['sentAt,desc'],
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/stock-transfers');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('direction')).toBe('out');
    expect(req.request.params.get('status')).toBe('SENT');
    expect(req.request.params.get('from')).toBe('2026-11-01');
    expect(req.request.params.get('to')).toBe('2026-11-18');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.getAll('sort')).toEqual(['sentAt,desc']);

    flushJson(req, {
      content: [{ id: 10, number: 'TR-001', status: 'SENT', lines: [] }],
      page: 1,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      sort: ['sentAt,desc'],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.content?.[0]?.number).toBe('TR-001');
  });

  it('createTransfer, updateTransfer, sendTransfer, receiveTransfer y cancelTransfer llaman a sus endpoints con idempotencia', async () => {
    const createPromise = firstValueFrom(
      service.createTransfer({
        destinationStoreId: 2,
        notes: 'Reposición',
        lines: [{ productId: 5, qtySent: 10 }],
      }),
    );
    const createReq = http.expectOne('/api/v1/stock-transfers');
    expect(createReq.request.method).toBe('POST');
    expect(createReq.request.context.get(IDEMPOTENT)).toBe(true);
    expect(createReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    flushJson(createReq, { id: 40, number: 'TR-040', status: 'DRAFT', version: 0, lines: [] });
    const created = await createPromise;
    expect(created.id).toBe(40);

    const updatePromise = firstValueFrom(
      service.updateTransfer(40, {
        destinationStoreId: 3,
        version: 0,
        lines: [{ productId: 5, qtySent: 8, originLotId: 99 }],
      }),
    );
    const updateReq = http.expectOne('/api/v1/stock-transfers/40');
    expect(updateReq.request.method).toBe('PUT');
    expect(updateReq.request.body).toEqual({
      destinationStoreId: 3,
      version: 0,
      lines: [{ productId: 5, qtySent: 8, originLotId: 99 }],
    });
    flushJson(updateReq, { id: 40, number: 'TR-040', status: 'DRAFT', version: 1, lines: [] });
    const updated = await updatePromise;
    expect(updated.version).toBe(1);

    const sendPromise = firstValueFrom(service.sendTransfer(40));
    const sendReq = http.expectOne('/api/v1/stock-transfers/40/send');
    expect(sendReq.request.method).toBe('POST');
    expect(sendReq.request.context.get(IDEMPOTENT)).toBe(true);
    expect(sendReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    flushJson(sendReq, { id: 40, number: 'TR-040', status: 'SENT', lines: [] });
    const sent = await sendPromise;
    expect(sent.status).toBe('SENT');

    const receivePromise = firstValueFrom(
      service.receiveTransfer(40, {
        lines: [{ lineId: 101, qtyReceived: 7, differenceReason: '1 unidad dañada' }],
      }),
    );
    const receiveReq = http.expectOne('/api/v1/stock-transfers/40/receive');
    expect(receiveReq.request.method).toBe('POST');
    expect(receiveReq.request.context.get(IDEMPOTENT)).toBe(true);
    expect(receiveReq.request.headers.get('Idempotency-Key')).toBeTruthy();
    flushJson(receiveReq, { id: 40, number: 'TR-040', status: 'RECEIVED', lines: [] });
    const received = await receivePromise;
    expect(received.status).toBe('RECEIVED');

    const cancelPromise = firstValueFrom(service.cancelTransfer(41, { reason: 'Error en pedido' }));
    const cancelReq = http.expectOne('/api/v1/stock-transfers/41/cancel');
    expect(cancelReq.request.method).toBe('POST');
    expect(cancelReq.request.body).toEqual({ reason: 'Error en pedido' });
    cancelReq.flush('');
    await cancelPromise;
  });

  it('getTransfer y consultas auxiliares (stores, products, lots, fefo) apuntan a los PATH exactos', async () => {
    const detailPromise = firstValueFrom(service.getTransfer(12));
    const detailReq = http.expectOne('/api/v1/stock-transfers/12');
    expect(detailReq.request.method).toBe('GET');
    flushJson(detailReq, { id: 12, number: 'TR-012', status: 'DRAFT', lines: [] });
    const detail = await detailPromise;
    expect(detail.id).toBe(12);

    const storesPromise = firstValueFrom(service.listStores());
    const storesReq = http.expectOne((r) => r.url === '/api/v1/stores');
    expect(storesReq.request.params.get('active')).toBe('true');
    flushJson(storesReq, { content: [{ id: 1, code: 'CTR', name: 'Centro', active: true }] });
    const stores = await storesPromise;
    expect(stores).toHaveLength(1);

    const productsPromise = firstValueFrom(service.searchProducts('leche'));
    const productsReq = http.expectOne((r) => r.url === '/api/v1/products');
    expect(productsReq.request.params.get('q')).toBe('leche');
    expect(productsReq.request.params.get('active')).toBe('true');
    flushJson(productsReq, { content: [{ id: 5, sku: 'LEC-01', name: 'Leche' }] });
    const products = await productsPromise;
    expect(products).toHaveLength(1);

    const productPromise = firstValueFrom(service.getProduct(5));
    const productReq = http.expectOne('/api/v1/products/5');
    flushJson(productReq, { id: 5, lotControlled: true, unitOfMeasure: 'UNIT' });
    const product = await productPromise;
    expect(product.lotControlled).toBe(true);

    const lotsPromise = firstValueFrom(service.listProductLots(5));
    const lotsReq = http.expectOne((r) => r.url === '/api/v1/lots');
    expect(lotsReq.request.params.get('productId')).toBe('5');
    expect(lotsReq.request.params.get('withStock')).toBe('true');
    flushJson(lotsReq, { content: [{ id: 70, lotNumber: 'L-01', quantity: 12 }] });
    const lots = await lotsPromise;
    expect(lots).toHaveLength(1);

    const fefoPromise = firstValueFrom(service.suggestFefo(5, 10));
    const fefoReq = http.expectOne((r) => r.url === '/api/v1/lots/fefo');
    expect(fefoReq.request.params.get('productId')).toBe('5');
    expect(fefoReq.request.params.get('quantity')).toBe('10');
    flushJson(fefoReq, {
      productId: 5,
      requestedQty: 10,
      allocations: [{ lotId: 70, lotNumber: 'L-01', quantity: 10 }],
      shortage: 0,
    });
    const fefo = await fefoPromise;
    expect(fefo.shortage).toBe(0);
  });

  describe('con errorInterceptor y respuestas ProblemDetail Blob o string JSON', () => {
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
      service = TestBed.inject(TransfersService);
      http = TestBed.inject(HttpTestingController);
    });

    it('desempaqueta ProblemDetail Blob en 400 VALIDATION_ERROR al recepcionar con diferencia sin motivo', async () => {
      const promise = firstValueFrom(
        service.receiveTransfer(
          40,
          { lines: [{ lineId: 101, qtyReceived: 4 }] },
          withoutErrorNotification(),
        ),
      );
      const req = http.expectOne('/api/v1/stock-transfers/40/receive');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'VALIDATION_ERROR',
              detail: 'Datos de recepción inválidos',
              errors: [
                {
                  field: 'lines[0].differenceReason',
                  code: 'DIFFERENCE_REASON_REQUIRED',
                  message:
                    'Debe indicar el motivo de la diferencia cuando lo recibido es menor a lo enviado',
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
            field: 'lines[0].differenceReason',
            code: 'DIFFERENCE_REASON_REQUIRED',
          },
        ],
      });
    });

    it('desempaqueta ProblemDetail string JSON en 403 ACCESS_DENIED', async () => {
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
                        code: 'ACCESS_DENIED',
                        detail: 'Solo el local de destino puede recepcionar la transferencia',
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
      service = TestBed.inject(TransfersService);
      http = TestBed.inject(HttpTestingController);

      const err = await firstValueFrom(
        service.receiveTransfer(
          40,
          { lines: [{ lineId: 101, qtyReceived: 5 }] },
          withoutErrorNotification(),
        ),
      ).catch((e: unknown) => e);

      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).code).toBe('ACCESS_DENIED');
      expect((err as ApiError).message).toBe(
        'Solo el local de destino puede recepcionar la transferencia',
      );
    });
  });
});
