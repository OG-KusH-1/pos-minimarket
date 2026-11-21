import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../api/api-configuration';
import { DashboardResponse } from '../../api/models/dashboard-response';
import { ApiError } from '../../core/http/api-error';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { DashboardService } from './dashboard.service';

/** Responde con un JSON envuelto en `Blob` (como espera `ng-openapi-gen` cuando `Accept: * / *`). */
function flushJsonBlob(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

/** Responde con un `ProblemDetail` RFC 9457 adaptado al `responseType: 'blob'`. */
function flushProblemBlob(req: TestRequest, status: number, body: unknown): void {
  const json = JSON.stringify(body);
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([json], { type: 'application/problem+json' }), {
      status,
      statusText: 'Error',
    });
  } else {
    req.flush(body as Record<string, unknown>, { status, statusText: 'Error' });
  }
}

describe('DashboardService', () => {
  let service: DashboardService;
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
    service = TestBed.inject(DashboardService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('getDashboard consulta GET /api/v1/reports/dashboard sin parámetros opcionales y desenvuelve Blob JSON', async () => {
    const sample: DashboardResponse = {
      date: '2026-11-20',
      salesTotal: 125000,
      transactions: 18,
      avgTicket: 6944,
      vsPreviousDayPct: 12.5,
      salesByHour: [{ hour: 10, total: 45000, transactions: 6 }],
      topProducts: [{ productId: 12, name: 'Pan Marraqueta 1 kg', qty: 14, total: 28000 }],
      paymentMix: [{ method: 'CASH', total: 85000 }],
      alerts: {
        belowMin: 4,
        zeroStock: 2,
        expiringLots: 3,
        pendingShrinkages: 1,
        pendingAdjustments: 1,
        pendingCounts: 1,
        pendingCashDifferences: 1,
      },
    };

    const promise = firstValueFrom(service.getDashboard(undefined, withoutErrorNotification()));

    const req = http.expectOne((r) => r.url === '/api/v1/reports/dashboard');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toEqual([]);

    flushJsonBlob(req, sample);

    const result = await promise;
    expect(result).toEqual(sample);
  });

  it('getDashboard envía date y múltiples storeIds cuando se especifican', async () => {
    const promise = firstValueFrom(
      service.getDashboard({ date: '2026-11-18', storeIds: [1, 2] }, withoutErrorNotification()),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/reports/dashboard');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('date')).toBe('2026-11-18');
    expect(req.request.params.getAll('storeIds')).toEqual(['1', '2']);

    flushJsonBlob(req, { date: '2026-11-18', salesTotal: 0, transactions: 0, avgTicket: 0 });
    expect((await promise).date).toBe('2026-11-18');
  });

  it('getDashboard omite date vacío y storeIds vacíos o inválidos', async () => {
    const promise = firstValueFrom(
      service.getDashboard({ date: '   ', storeIds: [] }, withoutErrorNotification()),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/reports/dashboard');
    expect(req.request.params.has('date')).toBe(false);
    expect(req.request.params.has('storeIds')).toBe(false);

    flushJsonBlob(req, { date: '2026-11-20', salesTotal: 0, transactions: 0, avgTicket: 0 });
    await promise;
  });

  it('exportDashboard descarga archivo XLSX/PDF y extrae nombre desde Content-Disposition', async () => {
    const xlsxPromise = firstValueFrom(
      service.exportDashboard(
        { date: '2026-11-20', storeIds: [1, 3], format: 'xlsx' },
        withoutErrorNotification(),
      ),
    );

    const xlsxReq = http.expectOne((r) => r.url === '/api/v1/reports/dashboard/export');
    expect(xlsxReq.request.method).toBe('GET');
    expect(xlsxReq.request.params.get('date')).toBe('2026-11-20');
    expect(xlsxReq.request.params.getAll('storeIds')).toEqual(['1', '3']);
    expect(xlsxReq.request.params.get('format')).toBe('xlsx');

    xlsxReq.flush(new Blob(['xlsx-bytes'], { type: 'application/octet-stream' }), {
      headers: {
        'Content-Disposition': 'attachment; filename="reporte-dashboard.xlsx"',
      },
    });

    const xlsxFile = await xlsxPromise;
    expect(xlsxFile.fileName).toBe('reporte-dashboard.xlsx');
    expect(xlsxFile.blob.size).toBeGreaterThan(0);

    const pdfPromise = firstValueFrom(
      service.exportDashboard({ format: 'pdf' }, withoutErrorNotification()),
    );

    const pdfReq = http.expectOne((r) => r.url === '/api/v1/reports/dashboard/export');
    expect(pdfReq.request.params.get('format')).toBe('pdf');
    pdfReq.flush(new Blob(['pdf-bytes'], { type: 'application/pdf' }));

    const pdfFile = await pdfPromise;
    expect(pdfFile.fileName).toBe('reporte-dashboard.pdf');
  });

  it('convierte ProblemDetail en Blob (403 STORE_ACCESS_DENIED y 400 VALIDATION_ERROR) en ApiError', async () => {
    const deniedPromise = firstValueFrom(
      service.getDashboard({ storeIds: [99] }, withoutErrorNotification()),
    );

    const deniedReq = http.expectOne((r) => r.url === '/api/v1/reports/dashboard');
    flushProblemBlob(deniedReq, 403, {
      code: 'STORE_ACCESS_DENIED',
      detail: 'No tiene acceso a uno o más locales solicitados.',
    });

    const deniedErr = await deniedPromise.catch((e: unknown) => e);
    expect(deniedErr).toBeInstanceOf(ApiError);
    expect((deniedErr as ApiError).status).toBe(403);
    expect((deniedErr as ApiError).code).toBe('STORE_ACCESS_DENIED');
    expect((deniedErr as ApiError).message).toContain('No tiene acceso');

    const validationPromise = firstValueFrom(
      service.exportDashboard({ format: 'pdf' }, withoutErrorNotification()),
    );

    const validationReq = http.expectOne((r) => r.url === '/api/v1/reports/dashboard/export');
    flushProblemBlob(validationReq, 400, {
      code: 'VALIDATION_ERROR',
      detail: 'Fecha inválida.',
      errors: [{ field: 'date', code: 'INVALID_FORMAT', message: 'Formato YYYY-MM-DD' }],
    });

    const validationErr = await validationPromise.catch((e: unknown) => e);
    expect(validationErr).toBeInstanceOf(ApiError);
    expect((validationErr as ApiError).code).toBe('VALIDATION_ERROR');
    expect((validationErr as ApiError).hasFieldErrors).toBe(true);
    expect((validationErr as ApiError).errors[0]?.field).toBe('date');
  });
});
