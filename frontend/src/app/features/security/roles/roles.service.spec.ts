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
import { ApiError, isApiError } from '../../../core/http/api-error';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { RolesService } from './roles.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('RolesService', () => {
  let service: RolesService;
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
    service = TestBed.inject(RolesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listRoles y getRole consultan /api/v1/roles', async () => {
    const listPromise = firstValueFrom(service.listRoles({ page: 0, size: 20 }));
    const listReq = http.expectOne((r) => r.url === '/api/v1/roles');
    expect(listReq.request.method).toBe('GET');
    flushJson(listReq, {
      content: [{ id: 1, code: 'ADMIN', name: 'Administrador' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    const page = await listPromise;
    expect(page.content?.length ?? 0).toBe(1);

    const getPromise = firstValueFrom(service.getRole(1));
    const getReq = http.expectOne('/api/v1/roles/1');
    expect(getReq.request.method).toBe('GET');
    flushJson(getReq, {
      id: 1,
      code: 'ADMIN',
      name: 'Administrador',
      permissionCodes: ['security:role:read'],
    });
    const role = await getPromise;
    expect(role.code).toBe('ADMIN');
  });

  it('createRole, updateRole y deleteRole ejecutan POST, PUT y DELETE', async () => {
    const createPromise = firstValueFrom(service.createRole({ code: 'BODEGA', name: 'Bodega' }));
    const postReq = http.expectOne('/api/v1/roles');
    expect(postReq.request.method).toBe('POST');
    flushJson(postReq, { id: 10, code: 'BODEGA', name: 'Bodega' });
    const created = await createPromise;
    expect(created.id).toBe(10);

    const updatePromise = firstValueFrom(
      service.updateRole(10, { code: 'BODEGA', name: 'Bodeguero' }),
    );
    const putReq = http.expectOne('/api/v1/roles/10');
    expect(putReq.request.method).toBe('PUT');
    flushJson(putReq, { id: 10, code: 'BODEGA', name: 'Bodeguero' });
    await updatePromise;

    const delPromise = firstValueFrom(service.deleteRole(10));
    const delReq = http.expectOne('/api/v1/roles/10');
    expect(delReq.request.method).toBe('DELETE');
    delReq.flush(null, { status: 204, statusText: 'No Content' });
    await delPromise;
  });

  it('listPermissions y replacePermissions gestionan los permisos del rol', async () => {
    const permsPromise = firstValueFrom(service.listPermissions());
    const permsReq = http.expectOne('/api/v1/permissions');
    expect(permsReq.request.method).toBe('GET');
    flushJson(permsReq, [{ code: 'sales:pos:operate', module: 'sales', description: 'Vender' }]);
    const perms = await permsPromise;
    expect(perms).toHaveLength(1);

    const replacePromise = firstValueFrom(service.replacePermissions(2, ['sales:pos:operate']));
    const putReq = http.expectOne('/api/v1/roles/2/permissions');
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({ permissionCodes: ['sales:pos:operate'] });
    flushJson(putReq, { id: 2, permissionCodes: ['sales:pos:operate'] });
    await replacePromise;
  });

  it('getRoleLimits y updateRoleLimits preservan null como ilimitado', async () => {
    const limitsPromise = firstValueFrom(service.getRoleLimits(1));
    const getReq = http.expectOne('/api/v1/roles/1/limits');
    expect(getReq.request.method).toBe('GET');
    flushJson(getReq, [{ code: 'DISCOUNT_ITEM_MAX_PCT', value: null }]);
    const limits = await limitsPromise;
    expect(limits[0]?.value).toBeNull();

    const updatePromise = firstValueFrom(
      service.updateRoleLimits(1, [
        { code: 'DISCOUNT_ITEM_MAX_PCT', value: null as unknown as number },
      ]),
    );
    const putReq = http.expectOne('/api/v1/roles/1/limits');
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({
      limits: [{ code: 'DISCOUNT_ITEM_MAX_PCT', value: null }],
    });
    flushJson(putReq, [{ code: 'DISCOUNT_ITEM_MAX_PCT', value: null }]);
    await updatePromise;
  });

  it('desempaqueta ProblemDetail desde un error Blob envuelto por errorInterceptor', async () => {
    const promise = firstValueFrom(
      service.createRole({ code: 'ADMIN', name: 'Admin' }, withoutErrorNotification()),
    );

    const req = http.expectOne('/api/v1/roles');
    const problem = {
      status: 409,
      code: 'DUPLICATE_ENTITY',
      detail: 'Ya existe un rol con ese código.',
    };
    req.flush(new Blob([JSON.stringify(problem)], { type: 'application/problem+json' }), {
      status: 409,
      statusText: 'Conflict',
    });

    let caught: unknown;
    try {
      await promise;
    } catch (err) {
      caught = err;
    }

    expect(isApiError(caught)).toBe(true);
    const apiErr = caught as ApiError;
    expect(apiErr.code).toBe('DUPLICATE_ENTITY');
    expect(apiErr.message).toBe('Ya existe un rol con ese código.');
    expect(snackBarOpen).not.toHaveBeenCalled();
  });
});
