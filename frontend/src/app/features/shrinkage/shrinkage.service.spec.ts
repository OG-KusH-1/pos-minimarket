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
import { ApiError } from '../../core/http/api-error';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { idempotencyInterceptor } from '../../core/http/idempotency.interceptor';
import { ShrinkageService } from './shrinkage.service';

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('ShrinkageService', () => {
  let service: ShrinkageService;
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
    service = TestBed.inject(ShrinkageService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listShrinkages consulta GET /api/v1/shrinkages con filtros y ordenamiento', async () => {
    const promise = firstValueFrom(
      service.listShrinkages({
        status: 'PENDING_APPROVAL',
        shrinkageType: 'DAMAGED',
        from: '2026-11-01',
        to: '2026-11-18',
        page: 0,
        size: 20,
        sort: ['occurredAt,desc'],
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/shrinkages');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('PENDING_APPROVAL');
    expect(req.request.params.get('shrinkageType')).toBe('DAMAGED');
    expect(req.request.params.get('from')).toBe('2026-11-01');
    expect(req.request.params.get('to')).toBe('2026-11-18');
    expect(req.request.params.getAll('sort')).toEqual(['occurredAt,desc']);

    flushJson(req, {
      content: [{ id: 1, number: 'L01-MR-000001', status: 'PENDING_APPROVAL' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['occurredAt,desc'],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.content?.[0]?.number).toBe('L01-MR-000001');
  });

  it('getShrinkage consulta GET /api/v1/shrinkages/{id}', async () => {
    const promise = firstValueFrom(service.getShrinkage(15));
    const req = http.expectOne('/api/v1/shrinkages/15');
    expect(req.request.method).toBe('GET');
    flushJson(req, { id: 15, number: 'L01-MR-000015', status: 'APPROVED' });

    const detail = await promise;
    expect(detail.id).toBe(15);
    expect(detail.status).toBe('APPROVED');
  });

  it('createShrinkage envía POST /api/v1/shrinkages con Idempotency-Key', async () => {
    const promise = firstValueFrom(
      service.createShrinkage({
        productId: 10,
        shrinkageType: 'BROKEN',
        quantity: 2,
        responsibleUserId: 3,
        occurredAt: '2026-11-18T12:00:00.000Z',
      }),
    );

    const req = http.expectOne('/api/v1/shrinkages');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toMatch(UUID_REGEX);
    expect(req.request.body).toEqual({
      productId: 10,
      shrinkageType: 'BROKEN',
      quantity: 2,
      responsibleUserId: 3,
      occurredAt: '2026-11-18T12:00:00.000Z',
    });

    flushJson(req, { id: 7, number: 'L01-MR-000007', status: 'APPROVED', autoApproved: true });
    const created = await promise;
    expect(created.id).toBe(7);
    expect(created.autoApproved).toBe(true);
  });

  it('approveShrinkage envía POST /api/v1/shrinkages/{id}/approve con X-Override-Token opcional', async () => {
    const promise = firstValueFrom(
      service.approveShrinkage(12, 'override-tok-123', withoutErrorNotification()),
    );

    const req = http.expectOne('/api/v1/shrinkages/12/approve');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Override-Token')).toBe('override-tok-123');
    flushJson(req, { id: 12, status: 'APPROVED' });

    const updated = await promise;
    expect(updated.status).toBe('APPROVED');
  });

  it('rejectShrinkage envía POST /api/v1/shrinkages/{id}/reject con motivo', async () => {
    const promise = firstValueFrom(
      service.rejectShrinkage(12, { reason: 'Error de conteo en bodega' }),
    );

    const req = http.expectOne('/api/v1/shrinkages/12/reject');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Error de conteo en bodega' });
    flushJson(req, { id: 12, status: 'REJECTED', rejectionReason: 'Error de conteo en bodega' });

    const updated = await promise;
    expect(updated.status).toBe('REJECTED');
  });

  it('uploadEvidence envía POST /api/v1/files con category=SHRINKAGE_EVIDENCE', async () => {
    const file = new File(['img-bytes'], 'evidencia.png', { type: 'image/png' });
    const promise = firstValueFrom(service.uploadEvidence(file, withoutErrorNotification()));

    const req = http.expectOne((r) => r.url === '/api/v1/files');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('category')).toBe('SHRINKAGE_EVIDENCE');
    flushJson(req, { id: 88, category: 'SHRINKAGE_EVIDENCE', originalName: 'evidencia.png' });

    const uploaded = await promise;
    expect(uploaded.id).toBe(88);
    expect(uploaded.originalName).toBe('evidencia.png');
  });

  it('convierte errores ProblemDetail en Blob a ApiError con código y campos', async () => {
    const promise = firstValueFrom(
      service.createShrinkage(
        {
          productId: 10,
          shrinkageType: 'EXPIRED',
          quantity: 1,
          responsibleUserId: 3,
          occurredAt: '2026-11-18T12:00:00.000Z',
        },
        withoutErrorNotification(),
      ),
    );

    const req = http.expectOne('/api/v1/shrinkages');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            status: 422,
            code: 'LOT_REQUIRED',
            detail: 'Debe seleccionar un lote.',
            errors: [{ field: 'lotId', code: 'LOT_REQUIRED', message: 'Lote obligatorio' }],
          }),
        ],
        { type: 'application/json' },
      ),
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    await expect(promise).rejects.toBeInstanceOf(ApiError);
  });
});
