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
import { SaleReturnRequest } from '../../../api/models/sale-return-request';
import { SaleVoidRequest } from '../../../api/models/sale-void-request';
import { ApiError } from '../../../core/http/api-error';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { ReturnsService } from './returns.service';

/** Vuelca una respuesta JSON respetando el `responseType: 'blob'` del cliente generado. */
function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('ReturnsService', () => {
  let service: ReturnsService;
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
    service = TestBed.inject(ReturnsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('busca la venta por folio/número y por id en los endpoints correctos (flujo 4 de F.4)', async () => {
    const byNumberPromise = firstValueFrom(service.getSaleByNumber('L01-V-000042'));
    const numberReq = http.expectOne('/api/v1/sales/by-number/L01-V-000042');
    expect(numberReq.request.method).toBe('GET');
    flushJson(numberReq, { id: 42, number: 'L01-V-000042', status: 'COMPLETED' });
    const byNumber = await byNumberPromise;
    expect(byNumber.id).toBe(42);
    expect(byNumber.status).toBe('COMPLETED');

    const byIdPromise = firstValueFrom(service.getSaleById(42));
    const idReq = http.expectOne('/api/v1/sales/42');
    expect(idReq.request.method).toBe('GET');
    flushJson(idReq, { id: 42, status: 'PARTIALLY_RETURNED' });
    const byId = await byIdPromise;
    expect(byId.status).toBe('PARTIALLY_RETURNED');
  });

  it('carga las líneas devolvibles con cantidades máximas (GET /sales/{id}/returnable)', async () => {
    const promise = firstValueFrom(service.getReturnableLines(42));
    const req = http.expectOne('/api/v1/sales/42/returnable');
    expect(req.request.method).toBe('GET');
    flushJson(req, {
      saleId: 42,
      voidAllowed: false,
      returnDeadline: '2026-12-01T23:59:59-03:00',
      lines: [
        {
          saleLineId: 7,
          name: 'Coca-Cola 1.5L',
          quantity: 3,
          returnedQuantity: 1,
          returnableQuantity: 2,
          unitRefundAmount: 1300,
        },
      ],
    });
    const returnable = await promise;
    expect(returnable.voidAllowed).toBe(false);
    expect(returnable.lines?.[0]?.returnableQuantity).toBe(2);
    expect(returnable.lines?.[0]?.unitRefundAmount).toBe(1300);
  });

  it('anula la venta con Idempotency-Key y agrega X-Override-Token solo si viene (POST /sales/{id}/void)', async () => {
    const body: SaleVoidRequest = { reasonCode: 'CASHIER_ERROR', reasonText: 'Cobro duplicado' };

    // Primer intento sin token: solo Idempotency-Key.
    const firstPromise = firstValueFrom(
      service.voidSale(42, body, { idempotencyKey: 'void-key-1' }),
    );
    const firstReq = http.expectOne('/api/v1/sales/42/void');
    expect(firstReq.request.method).toBe('POST');
    expect(firstReq.request.headers.get('Idempotency-Key')).toBe('void-key-1');
    expect(firstReq.request.headers.get('X-Override-Token')).toBeNull();
    expect(firstReq.request.body).toEqual(body);
    flushJson(firstReq, { id: 90, returnType: 'VOID', saleId: 42, refundTotal: 3900 });
    const first = await firstPromise;
    expect(first.returnType).toBe('VOID');
    expect(first.refundTotal).toBe(3900);

    // Reintento con override (el que orquesta SupervisorOverrideService): agrega el token.
    const retryPromise = firstValueFrom(
      service.voidSale(42, body, { idempotencyKey: 'void-key-1', overrideToken: 'tok-void-1' }),
    );
    const retryReq = http.expectOne('/api/v1/sales/42/void');
    expect(retryReq.request.headers.get('Idempotency-Key')).toBe('void-key-1');
    expect(retryReq.request.headers.get('X-Override-Token')).toBe('tok-void-1');
    flushJson(retryReq, { id: 90, returnType: 'VOID' });
    expect((await retryPromise).id).toBe(90);

    // Sin clave explícita se genera un UUID v4 nuevo por operación.
    const autoKeyPromise = firstValueFrom(service.voidSale(42, body));
    const autoKeyReq = http.expectOne('/api/v1/sales/42/void');
    expect(autoKeyReq.request.headers.get('Idempotency-Key')).toMatch(UUID_PATTERN);
    flushJson(autoKeyReq, { id: 91, returnType: 'VOID' });
    await autoKeyPromise;
  });

  it('crea la devolución en POST /sales/{id}/returns con el payload íntegro y sin refunds', async () => {
    const body: SaleReturnRequest = {
      reasonCode: 'DEFECTIVE',
      lines: [
        { saleLineId: 7, quantity: 2, restock: true },
        { saleLineId: 9, quantity: 0.5, restock: false },
      ],
    };

    const promise = firstValueFrom(
      service.createReturn(42, body, {
        idempotencyKey: 'return-key-1',
        overrideToken: 'tok-ret-1',
      }),
    );
    const req = http.expectOne('/api/v1/sales/42/returns');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toBe('return-key-1');
    expect(req.request.headers.get('X-Override-Token')).toBe('tok-ret-1');
    expect(req.request.body).toEqual(body);
    flushJson(req, {
      id: 91,
      number: 'L01-D-000091',
      saleId: 42,
      returnType: 'RETURN',
      refundTotal: 5750,
      lines: [
        { saleLineId: 7, name: 'Coca-Cola 1.5L', quantity: 2, restock: true, refundAmount: 2600 },
      ],
      refunds: [{ method: 'CASH', amount: 5750 }],
    });
    const response = await promise;
    expect(response.returnType).toBe('RETURN');
    expect(response.refunds?.[0]?.method).toBe('CASH');
  });

  it('traduce un 422 RETURN_WINDOW_EXPIRED a ApiError con su código de negocio', async () => {
    let captured: unknown = null;
    service.getReturnableLines(42).subscribe({
      error: (err: unknown) => {
        captured = err;
      },
    });
    const req = http.expectOne((r) => r.url === '/api/v1/sales/42/returnable');
    expect(req.request.responseType).toBe('blob');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            type: 'https://pos-minimarket/errors/return-window-expired',
            title: 'Plazo de devolución vencido',
            status: 422,
            detail: 'La venta superó el plazo máximo de devolución.',
            code: 'RETURN_WINDOW_EXPIRED',
            traceId: 'trace-1',
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    await vi.waitFor(() => {
      expect(captured).toBeInstanceOf(ApiError);
    });
    const apiError = captured as ApiError;
    expect(apiError.code).toBe('RETURN_WINDOW_EXPIRED');
    expect(apiError.status).toBe(422);
    expect(apiError.message).toContain('plazo');
  });
});
