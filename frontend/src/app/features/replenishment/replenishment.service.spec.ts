import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { provideApiConfiguration } from '../../api/api-configuration';
import { ApiError } from '../../core/http/api-error';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { idempotencyInterceptor } from '../../core/http/idempotency.interceptor';
import { ReplenishmentService } from './replenishment.service';

/** Envía una respuesta JSON al cliente generado, que declara `responseType: 'blob'`. */
function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

/**
 * Envía un ProblemDetail adaptado al `responseType` declarado por la función generada (`'blob'`
 * para endpoints con cuerpo `* / *`, `'text'` para respuestas `void` como DELETE 204).
 */
function flushProblemError(req: TestRequest, status: number, body: unknown): void {
  const json = JSON.stringify(body);
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([json], { type: 'application/problem+json' }), {
      status,
      statusText: 'Error',
    });
  } else if (req.request.responseType === 'text') {
    req.flush(json, { status, statusText: 'Error' });
  } else {
    req.flush(body as Record<string, unknown>, { status, statusText: 'Error' });
  }
}

describe('ReplenishmentService', () => {
  let service: ReplenishmentService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(ReplenishmentService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listParams consulta GET /api/v1/replenishment/params con el filtro de alcance', async () => {
    const promise = firstValueFrom(service.listParams('GLOBAL'));

    const req = http.expectOne((r) => r.url === '/api/v1/replenishment/params');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('scope')).toBe('GLOBAL');

    flushJson(req, [
      {
        id: 1,
        scope: 'GLOBAL',
        windowDays: 30,
        minHistoryDays: 14,
        enabled: true,
      },
    ]);

    const rows = await promise;
    expect(rows).toHaveLength(1);
    expect(rows[0]?.scope).toBe('GLOBAL');
    expect(rows[0]?.windowDays).toBe(30);
  });

  it('upsertParams envía PUT /api/v1/replenishment/params con el cuerpo del request', async () => {
    const body = {
      scope: 'STORE',
      storeId: 1,
      windowDays: 60,
      enabled: true,
    } as const;

    const promise = firstValueFrom(service.upsertParams(body));
    const req = http.expectOne('/api/v1/replenishment/params');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);

    flushJson(req, { id: 5, scope: 'STORE', storeId: 1, windowDays: 60, enabled: true });
    expect((await promise).id).toBe(5);
  });

  it('deleteParam ejecuta DELETE /api/v1/replenishment/params/{id} y tolera 204', async () => {
    const promise = firstValueFrom(service.deleteParam(9));

    const req = http.expectOne('/api/v1/replenishment/params/9');
    expect(req.request.method).toBe('DELETE');
    req.flush('', { status: 204, statusText: 'No Content' });

    await promise;
  });

  it('executeRun envía POST /api/v1/replenishment/runs [I] con Idempotency-Key', async () => {
    const promise = firstValueFrom(service.executeRun());

    const req = http.expectOne('/api/v1/replenishment/runs');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();

    flushJson(req, {
      id: 7,
      status: 'COMPLETED',
      storeId: 1,
      linesCount: 3,
      countsByStatus: { SUGGESTED: 2, NO_ACTION: 1 },
    });

    const created = await promise;
    expect(created.id).toBe(7);
    expect(created.status).toBe('COMPLETED');
  });

  it('listRuns consulta GET /api/v1/replenishment/runs con paginación y orden', async () => {
    const promise = firstValueFrom(service.listRuns({ page: 1, size: 10, sort: ['id,desc'] }));

    const req = http.expectOne((r) => r.url === '/api/v1/replenishment/runs');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.getAll('sort')).toEqual(['id,desc']);

    flushJson(req, {
      content: [{ id: 7, status: 'COMPLETED', linesCount: 3 }],
      page: 1,
      size: 10,
      totalElements: 11,
      totalPages: 2,
      sort: ['id,desc'],
    });

    const page = await promise;
    expect(page.content).toHaveLength(1);
    expect(page.totalElements).toBe(11);
  });

  it('getRun consulta GET /api/v1/replenishment/runs/{id}', async () => {
    const promise = firstValueFrom(service.getRun(7));

    const req = http.expectOne('/api/v1/replenishment/runs/7');
    expect(req.request.method).toBe('GET');

    flushJson(req, { id: 7, status: 'RUNNING', linesCount: 0 });
    expect((await promise).status).toBe('RUNNING');
  });

  it('listLines consulta GET /api/v1/replenishment/runs/{id}/lines con filtros', async () => {
    const promise = firstValueFrom(
      service.listLines({ id: 7, status: 'SUGGESTED', page: 0, size: 50 }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/replenishment/runs/7/lines');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('SUGGESTED');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('50');

    flushJson(req, {
      content: [
        {
          id: 101,
          status: 'SUGGESTED',
          suggestedPurchaseUnits: 6,
          product: { id: 42, sku: 'ACE-01', name: 'Aceite 1L' },
        },
      ],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
      sort: [],
    });

    const page = await promise;
    expect(page.content?.[0]?.product?.sku).toBe('ACE-01');
  });

  it('updateLine envía PATCH /api/v1/replenishment/lines/{id} con el payload de edición', async () => {
    const body = {
      finalPurchaseUnits: 12,
      productSupplierId: 33,
      status: 'ACCEPTED',
    } as const;

    const promise = firstValueFrom(service.updateLine(101, body));
    const req = http.expectOne('/api/v1/replenishment/lines/101');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual(body);

    flushJson(req, {
      id: 101,
      status: 'ACCEPTED',
      finalPurchaseUnits: 12,
      estimatedCost: 48000,
    });

    const updated = await promise;
    expect(updated.status).toBe('ACCEPTED');
    expect(updated.finalPurchaseUnits).toBe(12);
  });

  describe('generateDrafts (POST /replenishment/runs/{id}/purchase-orders, [I])', () => {
    it('envía lineIds con Idempotency-Key cuando hay selección', async () => {
      const promise = firstValueFrom(service.generateDrafts(7, { lineIds: [101, 102, 205] }));

      const req = http.expectOne('/api/v1/replenishment/runs/7/purchase-orders');
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();
      expect(req.request.body).toEqual({ lineIds: [101, 102, 205] });

      flushJson(req, {
        purchaseOrders: [
          {
            id: 900,
            number: 'OC-001-000900',
            supplierId: 3,
            supplierName: 'Distribuidora Norte',
            linesCount: 2,
            netTotal: 96000,
          },
          {
            id: 901,
            number: 'OC-001-000901',
            supplierId: 4,
            supplierName: 'Bodegas Sur',
            linesCount: 1,
            netTotal: 12000,
          },
        ],
      });

      const result = await promise;
      expect(result.purchaseOrders).toHaveLength(2);
      expect(result.purchaseOrders?.[0]?.number).toBe('OC-001-000900');
    });

    it('envía cuerpo vacío (lineIds omitido = default del backend) sin selección', async () => {
      const promise = firstValueFrom(service.generateDrafts(7));

      const req = http.expectOne('/api/v1/replenishment/runs/7/purchase-orders');
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();
      expect(req.request.body).toBeFalsy();

      flushJson(req, { purchaseOrders: [] });
      expect((await promise).purchaseOrders).toHaveLength(0);
    });
  });

  it('listProductSuppliers consulta GET /api/v1/products/{id}/suppliers [G]', async () => {
    const promise = firstValueFrom(service.listProductSuppliers({ id: 42 }));

    const req = http.expectOne('/api/v1/products/42/suppliers');
    expect(req.request.method).toBe('GET');

    flushJson(req, [
      {
        id: 33,
        productId: 42,
        supplierId: 3,
        supplierName: 'Distribuidora Norte',
        active: true,
        minOrderQty: 12,
        unitsPerPurchaseUnit: 12,
        purchaseUnitName: 'Caja',
      },
    ]);

    const suppliers = await promise;
    expect(suppliers).toHaveLength(1);
    expect(suppliers[0]?.minOrderQty).toBe(12);
  });

  it('listCategories consulta GET /api/v1/categories con tree=true', async () => {
    const promise = firstValueFrom(service.listCategories());

    const req = http.expectOne((r) => r.url === '/api/v1/categories');
    expect(req.request.params.get('tree')).toBe('true');

    flushJson(req, [{ id: 3, name: 'Lácteos', children: [{ id: 4, name: 'Yogurt' }] }]);
    expect(await promise).toHaveLength(1);
  });

  it('searchProducts consulta GET /api/v1/products con búsqueda por texto', async () => {
    const promise = firstValueFrom(service.searchProducts('aceite'));

    const req = http.expectOne((r) => r.url === '/api/v1/products');
    expect(req.request.params.get('q')).toBe('aceite');
    expect(req.request.params.get('active')).toBe('true');
    expect(req.request.params.getAll('sort')).toEqual(['name,asc']);

    flushJson(req, {
      content: [{ id: 42, sku: 'ACE-01', name: 'Aceite 1L' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      sort: ['name,asc'],
    });

    expect((await promise)[0]?.sku).toBe('ACE-01');
  });

  describe('con errorInterceptor y ProblemDetail', () => {
    it('convierte el 409 ENTITY_IN_USE del GLOBAL en ApiError con código y mensaje', async () => {
      const promise = firstValueFrom(service.deleteParam(1, withoutErrorNotification()));

      const req = http.expectOne('/api/v1/replenishment/params/1');
      flushProblemError(req, 409, {
        code: 'ENTITY_IN_USE',
        detail: 'No se puede borrar el parámetro GLOBAL.',
      });

      const err = await promise.catch((e: unknown) => e);
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).status).toBe(409);
      expect((err as ApiError).code).toBe('ENTITY_IN_USE');
      expect((err as ApiError).message).toContain('No se puede borrar');
    });

    it('convierte el 409 INVALID_STATE_TRANSITION de una línea ya en OC en ApiError', async () => {
      const promise = firstValueFrom(
        service.updateLine(
          101,
          { finalPurchaseUnits: 5, status: 'ACCEPTED' },
          withoutErrorNotification(),
        ),
      );

      const req = http.expectOne('/api/v1/replenishment/lines/101');
      flushProblemError(req, 409, {
        code: 'INVALID_STATE_TRANSITION',
        detail: 'La línea ya está incluida en una orden de compra.',
      });

      const err = await promise.catch((e: unknown) => e);
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).code).toBe('INVALID_STATE_TRANSITION');
      expect((err as ApiError).message).toBe('La línea ya está incluida en una orden de compra.');
    });

    it('convierte el 422 VALIDATION_ERROR del upsert en ApiError con detalle por campo', async () => {
      const promise = firstValueFrom(
        service.upsertParams(
          { scope: 'STORE', storeId: 1, windowDays: 9999 },
          withoutErrorNotification(),
        ),
      );

      const req = http.expectOne('/api/v1/replenishment/params');
      flushProblemError(req, 422, {
        code: 'VALIDATION_ERROR',
        detail: 'Los parámetros enviados no son válidos.',
        errors: [{ field: 'windowDays', code: 'RANGE', message: 'windowDays debe ser 1..730' }],
      });

      const err = await promise.catch((e: unknown) => e);
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).code).toBe('VALIDATION_ERROR');
      expect((err as ApiError).hasFieldErrors).toBe(true);
      expect((err as ApiError).errors[0]?.field).toBe('windowDays');
    });
  });
});
