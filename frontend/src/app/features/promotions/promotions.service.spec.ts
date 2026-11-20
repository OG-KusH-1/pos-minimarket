import { HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
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
import { PromotionsService } from './promotions.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('PromotionsService', () => {
  let service: PromotionsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PromotionsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listPromotions consulta GET /api/v1/promotions con filtros y desenvuelve el Blob JSON', async () => {
    const listPromise = firstValueFrom(
      service.listPromotions({ active: true, type: 'BUY_X_PAY_Y', page: 0, size: 20 }),
    );
    const listReq = http.expectOne((r) => r.url === '/api/v1/promotions');
    expect(listReq.request.method).toBe('GET');
    expect(listReq.request.params.get('active')).toBe('true');
    expect(listReq.request.params.get('type')).toBe('BUY_X_PAY_Y');
    flushJson(listReq, {
      content: [
        {
          id: 1,
          name: '3x2 bebidas',
          promotionType: 'BUY_X_PAY_Y',
          buyQty: 3,
          payQty: 2,
          active: true,
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    const page = await listPromise;
    expect(page.content?.[0]?.name).toBe('3x2 bebidas');
    expect(page.totalElements).toBe(1);
  });

  it('getPromotion consulta GET /api/v1/promotions/{id}', async () => {
    const getPromise = firstValueFrom(service.getPromotion(42));
    const getReq = http.expectOne('/api/v1/promotions/42');
    expect(getReq.request.method).toBe('GET');
    flushJson(getReq, {
      id: 42,
      name: 'Descuento 10% cárnico',
      promotionType: 'PERCENT',
      percent: 10,
      version: 3,
    });
    const detail = await getPromise;
    expect(detail.promotionType).toBe('PERCENT');
    expect(detail.version).toBe(3);
  });

  it('createPromotion y updatePromotion operan POST y PUT sobre /api/v1/promotions', async () => {
    const createBody = {
      name: 'Descuento $500 snacks',
      promotionType: 'FIXED_AMOUNT' as const,
      amount: 500,
      validFrom: '2026-11-19T13:00:00.000Z',
      items: [{ targetType: 'CATEGORY' as const, targetId: 8, requiredQty: 1 }],
    };
    const createPromise = firstValueFrom(service.createPromotion(createBody, new HttpContext()));
    const createReq = http.expectOne('/api/v1/promotions');
    expect(createReq.request.method).toBe('POST');
    expect(createReq.request.body).toMatchObject({
      name: 'Descuento $500 snacks',
      promotionType: 'FIXED_AMOUNT',
      amount: 500,
    });
    flushJson(createReq, { id: 9, name: 'Descuento $500 snacks', version: 1 });
    expect((await createPromise).id).toBe(9);

    const updatePromise = firstValueFrom(
      service.updatePromotion(9, { ...createBody, name: 'Descuento $600 snacks', version: 1 }),
    );
    const updateReq = http.expectOne('/api/v1/promotions/9');
    expect(updateReq.request.method).toBe('PUT');
    expect(updateReq.request.body.version).toBe(1);
    flushJson(updateReq, { id: 9, name: 'Descuento $600 snacks', version: 2 });
    expect((await updatePromise).version).toBe(2);
  });

  it('activatePromotion y deactivatePromotion hacen POST sobre /{id}/activate y /{id}/deactivate', async () => {
    const deactivatePromise = firstValueFrom(service.deactivatePromotion(9));
    const deactivateReq = http.expectOne('/api/v1/promotions/9/deactivate');
    expect(deactivateReq.request.method).toBe('POST');
    deactivateReq.flush('');
    await deactivatePromise;

    const activatePromise = firstValueFrom(service.activatePromotion(9));
    const activateReq = http.expectOne('/api/v1/promotions/9/activate');
    expect(activateReq.request.method).toBe('POST');
    activateReq.flush('');
    await activatePromise;
  });

  it('convierte un ProblemDetail recibido como Blob en ApiError tipado', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    const interceptedService = TestBed.inject(PromotionsService);
    const interceptedHttp = TestBed.inject(HttpTestingController);

    const errPromise = firstValueFrom(
      interceptedService.createPromotion(
        {
          name: 'Inválida',
          promotionType: 'PERCENT',
          percent: 150,
          validFrom: '2026-11-19T13:00:00.000Z',
          items: [{ targetType: 'PRODUCT', targetId: 1 }],
        },
        withoutErrorNotification(),
      ),
    ).catch((err: unknown) => err);

    const req = interceptedHttp.expectOne('/api/v1/promotions');
    req.flush(
      new Blob(
        [
          JSON.stringify({
            status: 422,
            code: 'VALIDATION_ERROR',
            detail: 'percent debe estar en (0, 100]',
            errors: [{ field: 'percent', code: 'RANGE', message: 'Debe estar entre 0 y 100' }],
          }),
        ],
        { type: 'application/problem+json' },
      ),
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    const err = await errPromise;
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('VALIDATION_ERROR');
    expect((err as ApiError).errors[0]?.field).toBe('percent');
    interceptedHttp.verify();
  });
});
