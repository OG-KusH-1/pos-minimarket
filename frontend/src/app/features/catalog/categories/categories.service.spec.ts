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
import { CategoriesService } from './categories.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('CategoriesService', () => {
  let service: CategoriesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideApiConfiguration(''), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CategoriesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listCategories consulta GET /api/v1/categories con el parámetro tree', async () => {
    const treePromise = firstValueFrom(service.listCategories(true));
    const treeReq = http.expectOne((r) => r.url === '/api/v1/categories');
    expect(treeReq.request.method).toBe('GET');
    expect(treeReq.request.params.get('tree')).toBe('true');
    flushJson(treeReq, [
      {
        id: 1,
        name: 'Bebidas',
        code: 'BEB',
        active: true,
        children: [{ id: 2, name: 'Gaseosas', parentId: 1, active: true, children: [] }],
      },
    ]);
    const tree = await treePromise;
    expect(tree).toHaveLength(1);
    expect(tree[0]?.children).toHaveLength(1);

    const flatPromise = firstValueFrom(service.listCategories(false));
    const flatReq = http.expectOne((r) => r.url === '/api/v1/categories');
    expect(flatReq.request.params.get('tree')).toBe('false');
    flushJson(flatReq, [{ id: 1, name: 'Bebidas', active: true }]);
    const flat = await flatPromise;
    expect(flat[0]?.name).toBe('Bebidas');
  });

  it('createCategory y updateCategory envían POST y PUT respectivamente', async () => {
    const createPromise = firstValueFrom(
      service.createCategory({ name: 'Aguas', code: 'AGU', parentId: 1 }),
    );
    const postReq = http.expectOne('/api/v1/categories');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.body).toEqual({ name: 'Aguas', code: 'AGU', parentId: 1 });
    flushJson(postReq, { id: 12, name: 'Aguas', code: 'AGU', parentId: 1, active: true });
    const created = await createPromise;
    expect(created.id).toBe(12);

    const updatePromise = firstValueFrom(
      service.updateCategory(12, { name: 'Aguas minerales', parentId: 1 }),
    );
    const putReq = http.expectOne('/api/v1/categories/12');
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({ name: 'Aguas minerales', parentId: 1 });
    flushJson(putReq, { id: 12, name: 'Aguas minerales', parentId: 1, active: true });
    const updated = await updatePromise;
    expect(updated.name).toBe('Aguas minerales');
  });

  it('deactivateCategory y activateCategory ejecutan POST en sus endpoints', async () => {
    const deactivatePromise = firstValueFrom(service.deactivateCategory(5));
    const deactivateReq = http.expectOne('/api/v1/categories/5/deactivate');
    expect(deactivateReq.request.method).toBe('POST');
    deactivateReq.flush(null, { status: 204, statusText: 'No Content' });
    await deactivatePromise;

    const activatePromise = firstValueFrom(service.activateCategory(5));
    const activateReq = http.expectOne('/api/v1/categories/5/activate');
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
      service = TestBed.inject(CategoriesService);
      http = TestBed.inject(HttpTestingController);
    });

    it('desempaqueta ProblemDetail Blob en VALIDATION_ERROR con errores por campo', async () => {
      const promise = firstValueFrom(
        service.createCategory({ name: 'X' }, withoutErrorNotification()),
      );
      const req = http.expectOne('/api/v1/categories');
      req.flush(
        new Blob(
          [
            JSON.stringify({
              code: 'VALIDATION_ERROR',
              detail: 'Datos inválidos',
              errors: [
                { field: 'name', code: 'SIZE', message: 'Debe tener entre 2 y 100 caracteres.' },
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
        errors: [{ field: 'name', code: 'SIZE', message: 'Debe tener entre 2 y 100 caracteres.' }],
      });
    });

    it('desempaqueta ProblemDetail en deactivateCategory cuando retorna 409 ENTITY_IN_USE', async () => {
      const promise = firstValueFrom(service.deactivateCategory(5));
      const req = http.expectOne('/api/v1/categories/5/deactivate');
      req.flush(
        JSON.stringify({
          code: 'ENTITY_IN_USE',
          detail: 'No se puede desactivar la categoría porque tiene productos activos.',
        }),
        { status: 409, statusText: 'Conflict' },
      );

      const error = await promise.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).code).toBe('ENTITY_IN_USE');
      expect((error as ApiError).message).toBe(
        'No se puede desactivar la categoría porque tiene productos activos.',
      );
    });
  });
});
