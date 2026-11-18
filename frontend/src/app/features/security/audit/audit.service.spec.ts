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
import { ApiError, isApiError } from '../../../core/http/api-error';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { AuditService } from './audit.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('AuditService', () => {
  let service: AuditService;
  let http: HttpTestingController;
  let snackBarOpen: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    snackBarOpen = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
      ],
    });
    service = TestBed.inject(AuditService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('listLogs envía filtros, paginación y orden como query params a GET /api/v1/audit-log', async () => {
    const promise = firstValueFrom(
      service.listLogs({
        from: '2026-11-01T00:00:00.000Z',
        to: '2026-11-14T23:59:59.999Z',
        action: 'USER_UPDATED',
        entityType: 'user',
        entityId: '15',
        userId: 3,
        storeId: 1,
        page: 2,
        size: 10,
        sort: ['occurredAt,desc', 'id,desc'],
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/audit-log');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('from')).toBe('2026-11-01T00:00:00.000Z');
    expect(req.request.params.get('to')).toBe('2026-11-14T23:59:59.999Z');
    expect(req.request.params.get('action')).toBe('USER_UPDATED');
    expect(req.request.params.get('entityType')).toBe('user');
    expect(req.request.params.get('entityId')).toBe('15');
    expect(req.request.params.get('userId')).toBe('3');
    expect(req.request.params.get('storeId')).toBe('1');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.getAll('sort')).toEqual(['occurredAt,desc', 'id,desc']);

    flushJson(req, {
      content: [
        {
          id: 99,
          occurredAt: '2026-11-14T12:00:00Z',
          action: 'USER_UPDATED',
          entityType: 'user',
          entityId: '15',
          username: 'admin',
        },
      ],
      page: 2,
      size: 10,
      totalElements: 21,
      totalPages: 3,
      sort: ['occurredAt,desc', 'id,desc'],
    });

    const res = await promise;
    expect(res.totalElements).toBe(21);
    expect(res.content?.[0]?.id).toBe(99);
  });

  it('getLog consulta GET /api/v1/audit-log/{id} y desenvuelve oldValue/newValue', async () => {
    const promise = firstValueFrom(service.getLog(44));

    const req = http.expectOne('/api/v1/audit-log/44');
    expect(req.request.method).toBe('GET');
    flushJson(req, {
      id: 44,
      occurredAt: '2026-11-14T10:15:00Z',
      userId: 1,
      username: 'admin',
      storeId: 2,
      terminalId: 5,
      ip: '127.0.0.1',
      action: 'PRODUCT_PRICE_CHANGED',
      entityType: 'product',
      entityId: '100',
      oldValue: { priceGross: 1200 },
      newValue: { priceGross: 1500 },
      reason: 'Reajuste proveedor',
      traceId: 'trace-xyz',
      hash: 'abc123def456',
    });

    const entry = await promise;
    expect(entry.id).toBe(44);
    expect(entry.hash).toBe('abc123def456');
    expect(entry.oldValue as unknown).toEqual({ priceGross: 1200 });
    expect(entry.newValue as unknown).toEqual({ priceGross: 1500 });
  });

  it('verifyChain envía POST /api/v1/audit-log/verify con rango opcional y retorna anomalías', async () => {
    const okPromise = firstValueFrom(service.verifyChain());
    const okReq = http.expectOne('/api/v1/audit-log/verify');
    expect(okReq.request.method).toBe('POST');
    expect(okReq.request.body).toEqual({});
    flushJson(okReq, { checked: 50, valid: true });

    const okRes = await okPromise;
    expect(okRes.valid).toBe(true);
    expect(okRes.checked).toBe(50);

    const brokenPromise = firstValueFrom(service.verifyChain({ fromId: 10, toId: 30 }));
    const brokenReq = http.expectOne('/api/v1/audit-log/verify');
    expect(brokenReq.request.method).toBe('POST');
    expect(brokenReq.request.body).toEqual({ fromId: 10, toId: 30 });
    flushJson(brokenReq, { checked: 7, valid: false, firstBrokenId: 16 });

    const brokenRes = await brokenPromise;
    expect(brokenRes.valid).toBe(false);
    expect(brokenRes.firstBrokenId).toBe(16);
  });

  it('listUsers obtiene usuarios ordenados para el selector de filtros', async () => {
    const promise = firstValueFrom(service.listUsers());
    const req = http.expectOne((r) => r.url === '/api/v1/users');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('size')).toBe('200');
    expect(req.request.params.getAll('sort')).toEqual(['username,asc']);
    flushJson(req, {
      content: [{ id: 1, username: 'admin', fullName: 'Administrador' }],
      page: 0,
      size: 200,
      totalElements: 1,
      totalPages: 1,
    });

    const users = await promise;
    expect(users).toHaveLength(1);
    expect(users[0]?.username).toBe('admin');
  });

  it('desempaqueta ProblemDetail desde un error Blob envuelto por errorInterceptor', async () => {
    const promise = firstValueFrom(
      service.verifyChain({ fromId: 20, toId: 10 }, withoutErrorNotification()),
    );

    const req = http.expectOne('/api/v1/audit-log/verify');
    const problem = {
      status: 400,
      code: 'VALIDATION_ERROR',
      detail: "El rango de verificación es inválido: 'fromId' no puede ser mayor que 'toId'.",
      errors: [{ field: 'fromId', code: 'INVALID_RANGE', message: "'fromId' <= 'toId'" }],
    };
    req.flush(new Blob([JSON.stringify(problem)], { type: 'application/problem+json' }), {
      status: 400,
      statusText: 'Bad Request',
    });

    let caught: unknown;
    try {
      await promise;
    } catch (err) {
      caught = err;
    }

    expect(isApiError(caught)).toBe(true);
    const apiErr = caught as ApiError;
    expect(apiErr.code).toBe('VALIDATION_ERROR');
    expect(apiErr.message).toContain('fromId');
    expect(apiErr.hasFieldErrors).toBe(true);
    expect(snackBarOpen).not.toHaveBeenCalled();
  });

  it('desempaqueta ProblemDetail desde un error string JSON envuelto por errorInterceptor', async () => {
    const problem = {
      status: 404,
      code: 'NOT_FOUND',
      detail: 'Registro de auditoría no encontrado.',
    };

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
                    error: JSON.stringify(problem),
                    status: 404,
                    statusText: 'Not Found',
                    url: r.url,
                  }),
              ),
          ]),
        ),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
      ],
    });
    service = TestBed.inject(AuditService);
    http = TestBed.inject(HttpTestingController);

    const promise = firstValueFrom(service.getLog(999, withoutErrorNotification()));

    let caught: unknown;
    try {
      await promise;
    } catch (err) {
      caught = err;
    }

    expect(isApiError(caught)).toBe(true);
    const apiErr = caught as ApiError;
    expect(apiErr.status).toBe(404);
    expect(apiErr.code).toBe('NOT_FOUND');
    expect(apiErr.message).toBe('Registro de auditoría no encontrado.');
    expect(snackBarOpen).not.toHaveBeenCalled();
  });
});
