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
import { BrandsService } from './brands.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('BrandsService', () => {
  let service: BrandsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BrandsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listBrands envía filtros y paginación a GET /api/v1/brands', async () => {
    const promise = firstValueFrom(
      service.listBrands({ q: 'coca', active: true, page: 0, size: 20, sort: ['name,asc'] }),
    );
    const req = http.expectOne((r) => r.url === '/api/v1/brands');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('coca');
    expect(req.request.params.get('active')).toBe('true');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.getAll('sort')).toEqual(['name,asc']);

    flushJson(req, {
      content: [{ id: 1, name: 'Coca-Cola', active: true }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    const page = await promise;
    expect(page.content?.[0]?.name).toBe('Coca-Cola');
  });

  it('createBrand y updateBrand envían POST y PUT respectivamente', async () => {
    const createPromise = firstValueFrom(service.createBrand({ name: 'Soprole' }));
    const postReq = http.expectOne('/api/v1/brands');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.body).toEqual({ name: 'Soprole' });
    flushJson(postReq, { id: 8, name: 'Soprole', active: true });
    const created = await createPromise;
    expect(created.id).toBe(8);

    const updatePromise = firstValueFrom(service.updateBrand(8, { name: 'Soprole Chile' }));
    const putReq = http.expectOne('/api/v1/brands/8');
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({ name: 'Soprole Chile' });
    flushJson(putReq, { id: 8, name: 'Soprole Chile', active: true });
    const updated = await updatePromise;
    expect(updated.name).toBe('Soprole Chile');
  });

  it('deactivateBrand y activateBrand ejecutan los endpoints POST correspondientes', async () => {
    const deactivatePromise = firstValueFrom(service.deactivateBrand(3));
    const deactivateReq = http.expectOne('/api/v1/brands/3/deactivate');
    expect(deactivateReq.request.method).toBe('POST');
    deactivateReq.flush(null, { status: 204, statusText: 'No Content' });
    await deactivatePromise;

    const activatePromise = firstValueFrom(service.activateBrand(3));
    const activateReq = http.expectOne('/api/v1/brands/3/activate');
    expect(activateReq.request.method).toBe('POST');
    activateReq.flush(null, { status: 204, statusText: 'No Content' });
    await activatePromise;
  });

  describe('con errorInterceptor y respuestas Blob', () => {
    beforeEach(() => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          provideApiConfiguration(''),
          provideHttpClient(withInterceptors([errorInterceptor])),
          provideHttpClientTesting(),
          { provide: MatSnackBar, useValue: { open: vi.fn() } },
        ],
      });
      service = TestBed.inject(BrandsService);
      http = TestBed.inject(HttpTestingController);
    });

    it('desempaqueta ProblemDetail Blob en DUPLICATED_VALUE al crear marca', async () => {
      const promise = firstValueFrom(
        service.createBrand({ name: 'Soprole' }, withoutErrorNotification()),
      );
      const req = http.expectOne('/api/v1/brands');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'DUPLICATED_VALUE',
              detail: 'Ya existe una marca con ese nombre.',
            }),
          ],
          { type: 'application/problem+json' },
        ),
        { status: 409, statusText: 'Conflict' },
      );

      const error = await promise.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).code).toBe('DUPLICATED_VALUE');
      expect((error as ApiError).message).toBe('Ya existe una marca con ese nombre.');
    });

    it('desempaqueta ProblemDetail en deactivateBrand cuando falla con 409', async () => {
      const promise = firstValueFrom(service.deactivateBrand(3));
      const req = http.expectOne('/api/v1/brands/3/deactivate');
      req.flush(
        JSON.stringify({
          code: 'ENTITY_IN_USE',
          detail: 'La marca tiene productos activos.',
        }),
        { status: 409, statusText: 'Conflict' },
      );

      const error = await promise.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).code).toBe('ENTITY_IN_USE');
      expect((error as ApiError).message).toBe('La marca tiene productos activos.');
    });
  });
});
