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
import { CashService } from './cash.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('CashService', () => {
  let service: CashService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(CashService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('openSession y getCurrentSession manejan apertura, 200 y 204 No Content', async () => {
    const openPromise = firstValueFrom(service.openSession({ openingAmount: 50000 }));
    const openReq = http.expectOne('/api/v1/cash-sessions');
    expect(openReq.request.method).toBe('POST');
    expect(openReq.request.headers.get('Idempotency-Key')).toMatch(UUID_PATTERN);
    expect(openReq.request.body).toEqual({ openingAmount: 50000 });
    flushJson(openReq, { id: 10, number: 'L01-TU-0001', status: 'OPEN', openingAmount: 50000 });
    const opened = await openPromise;
    expect(opened.id).toBe(10);

    const currentPromise = firstValueFrom(service.getCurrentSession());
    const currentReq = http.expectOne('/api/v1/cash-sessions/current');
    expect(currentReq.request.method).toBe('GET');
    flushJson(currentReq, { id: 10, number: 'L01-TU-0001', status: 'OPEN' });
    expect((await currentPromise)?.number).toBe('L01-TU-0001');

    const emptyPromise = firstValueFrom(service.getCurrentSession());
    const emptyReq = http.expectOne('/api/v1/cash-sessions/current');
    emptyReq.flush(null, { status: 204, statusText: 'No Content' });
    expect(await emptyPromise).toBeNull();
  });

  it('listSessions, getSession, getSummary y listMovements envían filtros y endpoints correctos', async () => {
    const listPromise = firstValueFrom(
      service.listSessions({
        from: '2026-11-01',
        to: '2026-11-14',
        cashierId: 3,
        terminalId: 2,
        status: 'CLOSED',
        withDifference: true,
        page: 1,
        size: 10,
        sort: ['openedAt,desc'],
      }),
    );
    const listReq = http.expectOne((r) => r.url === '/api/v1/cash-sessions');
    expect(listReq.request.method).toBe('GET');
    expect(listReq.request.params.get('from')).toBe('2026-11-01');
    expect(listReq.request.params.get('to')).toBe('2026-11-14');
    expect(listReq.request.params.get('cashierId')).toBe('3');
    expect(listReq.request.params.get('terminalId')).toBe('2');
    expect(listReq.request.params.get('status')).toBe('CLOSED');
    expect(listReq.request.params.get('withDifference')).toBe('true');
    expect(listReq.request.params.get('page')).toBe('1');
    expect(listReq.request.params.get('size')).toBe('10');
    expect(listReq.request.params.getAll('sort')).toEqual(['openedAt,desc']);
    flushJson(listReq, { content: [{ id: 10 }], page: 1, size: 10, totalElements: 1 });
    expect((await listPromise).content?.[0]?.id).toBe(10);

    const getPromise = firstValueFrom(service.getSession(10));
    const getReq = http.expectOne('/api/v1/cash-sessions/10');
    flushJson(getReq, { id: 10, status: 'OPEN' });
    expect((await getPromise).status).toBe('OPEN');

    const summaryPromise = firstValueFrom(service.getSummary(10));
    const summaryReq = http.expectOne('/api/v1/cash-sessions/10/summary');
    flushJson(summaryReq, {
      sessionId: 10,
      openingAmount: 50000,
      cashIn: 10000,
      cashOut: 5000,
      expectedCash: 55000,
      byMethod: [{ method: 'CASH', expected: 55000 }],
    });
    expect((await summaryPromise).expectedCash).toBe(55000);

    const movsPromise = firstValueFrom(service.listMovements(10));
    const movsReq = http.expectOne('/api/v1/cash-sessions/10/movements');
    flushJson(movsReq, [{ id: 1, movementType: 'CASH_IN', amount: 10000 }]);
    expect((await movsPromise)[0]?.movementType).toBe('CASH_IN');
  });

  it('registerMovement, closeSession, approveDifference y comprobantes JSON/PDF operan sus endpoints', async () => {
    const movPromise = firstValueFrom(
      service.registerMovement(
        10,
        { movementType: 'CASH_OUT', amount: 150000, reason: 'Retiro a bóveda' },
        { idempotencyKey: 'key-123', overrideToken: 'ov-tok-1' },
      ),
    );
    const movReq = http.expectOne('/api/v1/cash-sessions/10/movements');
    expect(movReq.request.method).toBe('POST');
    expect(movReq.request.headers.get('Idempotency-Key')).toBe('key-123');
    expect(movReq.request.headers.get('X-Override-Token')).toBe('ov-tok-1');
    flushJson(movReq, { id: 5, movementType: 'CASH_OUT', amount: 150000, authorizationId: 88 });
    expect((await movPromise).authorizationId).toBe(88);

    const closePromise = firstValueFrom(
      service.closeSession(
        10,
        {
          counts: [
            {
              paymentMethod: 'CASH',
              countedAmount: 48000,
              denominationDetail: { '20000': 2, '5000': 1, '1000': 3 },
            },
          ],
          notes: 'Faltante menor',
        },
        { overrideToken: 'ov-close-1' },
      ),
    );
    const closeReq = http.expectOne('/api/v1/cash-sessions/10/close');
    expect(closeReq.request.method).toBe('POST');
    expect(closeReq.request.headers.get('Idempotency-Key')).toMatch(UUID_PATTERN);
    expect(closeReq.request.headers.get('X-Override-Token')).toBe('ov-close-1');
    flushJson(closeReq, { id: 10, status: 'CLOSED', differenceTotal: -2000 });
    expect((await closePromise).differenceTotal).toBe(-2000);

    const approvePromise = firstValueFrom(service.approveDifference(10));
    const approveReq = http.expectOne('/api/v1/cash-sessions/10/approve-difference');
    expect(approveReq.request.method).toBe('POST');
    flushJson(approveReq, { id: 10, requiresDifferenceApproval: false });
    expect((await approvePromise).requiresDifferenceApproval).toBe(false);

    const receiptJsonPromise = firstValueFrom(service.getClosingReceiptJson(10));
    const jsonReq = http.expectOne(
      (r) =>
        r.url === '/api/v1/cash-sessions/10/closing-receipt' && r.params.get('format') === 'json',
    );
    expect(jsonReq.request.responseType).toBe('json');
    flushJson(jsonReq, { session: { id: 10 }, summary: { expectedCash: 50000 } });
    expect((await receiptJsonPromise).session?.id).toBe(10);

    const receiptPdfPromise = firstValueFrom(service.downloadClosingReceiptPdf(10));
    const pdfReq = http.expectOne(
      (r) =>
        r.url === '/api/v1/cash-sessions/10/closing-receipt' && r.params.get('format') === 'pdf',
    );
    expect(pdfReq.request.responseType).toBe('blob');
    const pdfBlob = new Blob(['%PDF-1.4'], { type: 'application/pdf' });
    pdfReq.flush(pdfBlob);
    expect(await receiptPdfPromise).toBe(pdfBlob);
  });

  it('getCashSettings, listTerminals y listStoreUsers cargan configuración y catálogos auxiliares', async () => {
    const settingsPromise = firstValueFrom(service.getCashSettings());
    const settingsReq = http.expectOne('/api/v1/settings/effective');
    flushJson(settingsReq, {
      values: { 'cash.difference_tolerance': 1000, 'cash.default_opening_amount': 35000 },
    });
    expect(await settingsPromise).toEqual({
      differenceTolerance: 1000,
      defaultOpeningAmount: 35000,
    });

    const termPromise = firstValueFrom(service.listTerminals(2));
    const termReq = http.expectOne('/api/v1/stores/2/terminals');
    flushJson(termReq, [{ id: 4, code: 'T01', name: 'Caja 1', active: true }]);
    expect((await termPromise)[0]?.code).toBe('T01');

    const usersPromise = firstValueFrom(service.listStoreUsers(2));
    const usersReq = http.expectOne((r) => r.url === '/api/v1/users');
    expect(usersReq.request.params.get('storeId')).toBe('2');
    flushJson(usersReq, { content: [{ id: 7, username: 'cajero1', fullName: 'Ana Soto' }] });
    expect((await usersPromise)[0]?.username).toBe('cajero1');
  });

  describe('con errorInterceptor y ProblemDetail Blob o string', () => {
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
      service = TestBed.inject(CashService);
      http = TestBed.inject(HttpTestingController);
    });

    it('desempaqueta ProblemDetail Blob en OVERRIDE_REQUIRED preservando action y context', async () => {
      const promise = firstValueFrom(
        service.registerMovement(
          10,
          { movementType: 'CASH_OUT', amount: 150000, reason: 'Retiro mayor' },
          { context: withoutErrorNotification() },
        ),
      );
      const req = http.expectOne('/api/v1/cash-sessions/10/movements');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              status: 422,
              code: 'OVERRIDE_REQUIRED',
              detail: 'El retiro supera el límite permitido para su rol.',
              action: 'CASH_OUT_OVER_LIMIT',
              context: { cashSessionId: 10, amount: 150000 },
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 422, statusText: 'Unprocessable Entity' },
      );

      const err = (await promise.catch((e: unknown) => e)) as ApiError;
      expect(err).toBeInstanceOf(ApiError);
      expect(err.code).toBe('OVERRIDE_REQUIRED');
      expect(err.message).toContain('supera el límite');
      expect(err.extensions).toEqual({
        action: 'CASH_OUT_OVER_LIMIT',
        context: { cashSessionId: 10, amount: 150000 },
      });
    });

    it('desempaqueta ProblemDetail Blob en VALIDATION_ERROR con errores por campo', async () => {
      const promise = firstValueFrom(
        service.openSession({ openingAmount: -10 }, withoutErrorNotification()),
      );
      const req = http.expectOne('/api/v1/cash-sessions');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              status: 400,
              code: 'VALIDATION_ERROR',
              detail: 'El monto de apertura no puede ser negativo.',
              errors: [
                {
                  field: 'openingAmount',
                  code: 'VALIDATION_ERROR',
                  message: 'Debe ser mayor o igual a 0.',
                },
              ],
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 400, statusText: 'Bad Request' },
      );

      const err = (await promise.catch((e: unknown) => e)) as ApiError;
      expect(err).toBeInstanceOf(ApiError);
      expect(err.code).toBe('VALIDATION_ERROR');
      expect(err.hasFieldErrors).toBe(true);
      expect(err.errors).toEqual([
        {
          field: 'openingAmount',
          code: 'VALIDATION_ERROR',
          message: 'Debe ser mayor o igual a 0.',
        },
      ]);
    });

    it('desempaqueta ProblemDetail Blob en SEGREGATION_OF_DUTIES al aprobar diferencia', async () => {
      const promise = firstValueFrom(service.approveDifference(10, withoutErrorNotification()));
      const req = http.expectOne('/api/v1/cash-sessions/10/approve-difference');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              status: 422,
              code: 'SEGREGATION_OF_DUTIES',
              detail: 'El cajero del turno no puede aprobar su propia diferencia de cierre.',
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 422, statusText: 'Unprocessable Entity' },
      );

      const err = (await promise.catch((e: unknown) => e)) as ApiError;
      expect(err).toBeInstanceOf(ApiError);
      expect(err.code).toBe('SEGREGATION_OF_DUTIES');
      expect(err.message).toContain('propia diferencia');
    });

    it('desempaqueta ProblemDetail string JSON dentro de ApiError.cause', async () => {
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
                        status: 422,
                        code: 'CASH_SESSION_ALREADY_OPEN',
                        detail: 'La terminal ya tiene un turno de caja abierto.',
                      }),
                      status: 422,
                      statusText: 'Unprocessable Entity',
                      url: req.url,
                    }),
                ),
            ]),
          ),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn() } },
        ],
      });
      service = TestBed.inject(CashService);
      http = TestBed.inject(HttpTestingController);

      const err = (await firstValueFrom(
        service.openSession({ openingAmount: 10000 }, withoutErrorNotification()),
      ).catch((e: unknown) => e)) as ApiError;

      expect(err).toBeInstanceOf(ApiError);
      expect(err.code).toBe('CASH_SESSION_ALREADY_OPEN');
      expect(err.message).toBe('La terminal ya tiene un turno de caja abierto.');
    });
  });
});
