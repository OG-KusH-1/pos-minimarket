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
import { UsersService } from './users.service';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('UsersService', () => {
  let service: UsersService;
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
    service = TestBed.inject(UsersService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listUsers envía los filtros y la paginación como query params', async () => {
    const promise = firstValueFrom(
      service.listUsers({
        q: 'ana',
        roleId: 2,
        storeId: 10,
        active: true,
        locked: false,
        page: 1,
        size: 10,
      }),
    );

    const req = http.expectOne((r) => r.url === '/api/v1/users');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('ana');
    expect(req.request.params.get('roleId')).toBe('2');
    expect(req.request.params.get('storeId')).toBe('10');
    expect(req.request.params.get('active')).toBe('true');
    expect(req.request.params.get('locked')).toBe('false');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');

    flushJson(req, { content: [], page: 1, size: 10, totalElements: 3, totalPages: 1 });
    const page = await promise;
    expect(page.totalElements).toBe(3);
  });

  it('createUser envía POST /api/v1/users y retorna la contraseña temporal', async () => {
    const promise = firstValueFrom(
      service.createUser({
        username: 'cajero2',
        fullName: 'Cajero Dos',
        roleIds: [3],
        storeIds: [10],
        defaultStoreId: 10,
      }),
    );

    const req = http.expectOne('/api/v1/users');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.username).toBe('cajero2');
    flushJson(req, {
      id: 42,
      username: 'cajero2',
      temporaryPassword: 'TempPassword1234',
      mustChangePassword: true,
    });

    const res = await promise;
    expect(res.temporaryPassword).toBe('TempPassword1234');
  });

  it('updateUser envía PUT /api/v1/users/{id}', async () => {
    const promise = firstValueFrom(
      service.updateUser(7, {
        username: 'cajero2',
        fullName: 'Cajero Actualizado',
        roleIds: [3],
        storeIds: [10],
        defaultStoreId: 10,
        version: 1,
      }),
    );

    const req = http.expectOne('/api/v1/users/7');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.version).toBe(1);
    flushJson(req, { id: 7, fullName: 'Cajero Actualizado' });

    const res = await promise;
    expect(res.fullName).toBe('Cajero Actualizado');
  });

  it('disableUser, enableUser, resetPin y unlockUser invocan sus endpoints POST', async () => {
    const p1 = firstValueFrom(service.disableUser(5));
    const disableReq = http.expectOne('/api/v1/users/5/disable');
    expect(disableReq.request.method).toBe('POST');
    disableReq.flush(null, { status: 204, statusText: 'No Content' });
    await p1;

    const p2 = firstValueFrom(service.enableUser(5));
    const enableReq = http.expectOne('/api/v1/users/5/enable');
    expect(enableReq.request.method).toBe('POST');
    enableReq.flush(null, { status: 204, statusText: 'No Content' });
    await p2;

    const p3 = firstValueFrom(service.resetPin(5));
    const pinReq = http.expectOne('/api/v1/users/5/reset-pin');
    expect(pinReq.request.method).toBe('POST');
    pinReq.flush(null, { status: 204, statusText: 'No Content' });
    await p3;

    const p4 = firstValueFrom(service.unlockUser(5));
    const unlockReq = http.expectOne('/api/v1/users/5/unlock');
    expect(unlockReq.request.method).toBe('POST');
    unlockReq.flush(null, { status: 204, statusText: 'No Content' });
    await p4;
  });

  it('resetPassword retorna TemporaryPasswordResponse', async () => {
    const promise = firstValueFrom(service.resetPassword(5));

    const req = http.expectOne('/api/v1/users/5/reset-password');
    expect(req.request.method).toBe('POST');
    flushJson(req, { temporaryPassword: 'NewTempPassword1', mustChangePassword: true });

    const res = await promise;
    expect(res.temporaryPassword).toBe('NewTempPassword1');
  });

  it('listSessions y revokeSessions consultan y revocan las sesiones del usuario', async () => {
    const listPromise = firstValueFrom(service.listSessions(9));
    const listReq = http.expectOne('/api/v1/users/9/sessions');
    expect(listReq.request.method).toBe('GET');
    flushJson(listReq, [{ id: 100, ip: '127.0.0.1' }]);
    const list = await listPromise;
    expect(list).toHaveLength(1);

    const revokePromise = firstValueFrom(service.revokeSessions(9));
    const delReq = http.expectOne('/api/v1/users/9/sessions');
    expect(delReq.request.method).toBe('DELETE');
    delReq.flush(null, { status: 204, statusText: 'No Content' });
    await revokePromise;
  });

  it('listStores obtiene los locales activos', async () => {
    const promise = firstValueFrom(service.listStores());

    const req = http.expectOne((r) => r.url === '/api/v1/stores');
    expect(req.request.params.get('active')).toBe('true');
    flushJson(req, {
      content: [{ id: 1, code: 'L01', name: 'Central' }],
      page: 0,
      size: 200,
      totalElements: 1,
      totalPages: 1,
    });

    const stores = await promise;
    expect(stores.map((s) => s.name)).toEqual(['Central']);
  });

  it('desempaqueta ProblemDetail desde un error Blob envuelto por errorInterceptor', async () => {
    const promise = firstValueFrom(
      service.createUser(
        {
          username: 'duplicado',
          fullName: 'Usuario Duplicado',
          roleIds: [3],
          storeIds: [10],
          defaultStoreId: 10,
        },
        withoutErrorNotification(),
      ),
    );

    const req = http.expectOne('/api/v1/users');
    const problem = {
      status: 400,
      code: 'VALIDATION_ERROR',
      detail: 'Error de validación',
      errors: [{ field: 'username', code: 'DUPLICATE', message: 'El usuario ya existe' }],
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
    expect(apiErr.hasFieldErrors).toBe(true);
    expect(apiErr.errors).toEqual([
      { field: 'username', code: 'DUPLICATE', message: 'El usuario ya existe' },
    ]);
    expect(snackBarOpen).not.toHaveBeenCalled();
  });
});
