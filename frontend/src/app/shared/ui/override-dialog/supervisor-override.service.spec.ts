import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom, of } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { errorInterceptor } from '../../../core/http/error.interceptor';
import { withIdempotency } from '../../../core/http/http-context-tokens';
import { idempotencyInterceptor } from '../../../core/http/idempotency.interceptor';
import { OverrideResponse } from './override.models';
import { SupervisorOverrideService } from './supervisor-override.service';

const OVERRIDE_REQUIRED_BODY = {
  status: 422,
  code: 'OVERRIDE_REQUIRED',
  detail: 'Se requiere autorización de un supervisor',
  action: 'SALE_RETURN',
  context: { saleId: 3, amount: 5000 },
};
const GRANTED: OverrideResponse = {
  overrideToken: 'tok-123',
  authorizationId: 1,
  expiresAt: '2026-01-01T00:00:00Z',
};
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('SupervisorOverrideService.withSupervisorOverride', () => {
  let service: SupervisorOverrideService;
  let client: HttpClient;
  let http: HttpTestingController;
  let openDialog: ReturnType<typeof vi.fn>;

  /** Simula el cierre del diálogo con la respuesta indicada (`undefined` = cancelado). */
  function dialogReturns(value: OverrideResponse | undefined): void {
    openDialog.mockReturnValue({ afterClosed: () => of(value) });
  }

  beforeEach(() => {
    openDialog = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor, idempotencyInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: { open: openDialog } },
      ],
    });
    service = TestBed.inject(SupervisorOverrideService);
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** La operación de negocio de los ejemplos: el contexto se crea UNA vez, fuera de la función. */
  function startReturn(): Promise<unknown> {
    const context = withIdempotency();
    return firstValueFrom(
      service.withSupervisorOverride((headers) =>
        client.post('/api/v1/sales/3/returns', { qty: 1 }, { headers, context }),
      ),
    );
  }

  it('no abre el diálogo si la operación tiene éxito', async () => {
    const result = startReturn();

    http.expectOne('/api/v1/sales/3/returns').flush({ id: 1 });

    expect(await result).toEqual({ id: 1 });
    expect(openDialog).not.toHaveBeenCalled();
  });

  it('ante OVERRIDE_REQUIRED abre el diálogo y repite con X-Override-Token y la misma Idempotency-Key', async () => {
    dialogReturns(GRANTED);
    const result = startReturn();

    const first = http.expectOne('/api/v1/sales/3/returns');
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toMatch(UUID);
    expect(first.request.headers.has('X-Override-Token')).toBe(false);
    first.flush(OVERRIDE_REQUIRED_BODY, { status: 422, statusText: 'Unprocessable Entity' });

    const retry = http.expectOne('/api/v1/sales/3/returns');
    expect(retry.request.headers.get('X-Override-Token')).toBe('tok-123');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    expect(retry.request.body).toEqual({ qty: 1 });
    retry.flush({ id: 99 });

    expect(await result).toEqual({ id: 99 });
    expect(openDialog).toHaveBeenCalledTimes(1);
    const config = openDialog.mock.calls[0]?.[1] as { data: unknown };
    expect(config.data).toEqual({
      action: 'SALE_RETURN',
      context: { saleId: 3, amount: 5000 },
    });
  });

  it('si se cancela el diálogo propaga el error original y no repite la operación', async () => {
    dialogReturns(undefined);
    const result = startReturn();

    http
      .expectOne('/api/v1/sales/3/returns')
      .flush(OVERRIDE_REQUIRED_BODY, { status: 422, statusText: 'Unprocessable Entity' });

    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('OVERRIDE_REQUIRED');
    http.expectNone('/api/v1/sales/3/returns');
  });

  it('propaga sin tocar los errores que no son OVERRIDE_REQUIRED', async () => {
    const result = startReturn();

    http
      .expectOne('/api/v1/sales/3/returns')
      .flush({ status: 403, code: 'ACCESS_DENIED' }, { status: 403, statusText: 'Forbidden' });

    const error = await result.catch((e: unknown) => e);
    expect((error as ApiError).code).toBe('ACCESS_DENIED');
    expect(openDialog).not.toHaveBeenCalled();
  });

  it('propaga el OVERRIDE_REQUIRED si no trae action y context', async () => {
    const result = startReturn();

    http
      .expectOne('/api/v1/sales/3/returns')
      .flush(
        { status: 422, code: 'OVERRIDE_REQUIRED' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );

    const error = await result.catch((e: unknown) => e);
    expect((error as ApiError).code).toBe('OVERRIDE_REQUIRED');
    expect(openDialog).not.toHaveBeenCalled();
  });

  it('no entra en bucle si el reintento vuelve a fallar con OVERRIDE_REQUIRED', async () => {
    dialogReturns(GRANTED);
    const result = startReturn();

    http
      .expectOne('/api/v1/sales/3/returns')
      .flush(OVERRIDE_REQUIRED_BODY, { status: 422, statusText: 'Unprocessable Entity' });
    http
      .expectOne('/api/v1/sales/3/returns')
      .flush(OVERRIDE_REQUIRED_BODY, { status: 422, statusText: 'Unprocessable Entity' });

    const error = await result.catch((e: unknown) => e);
    expect((error as ApiError).code).toBe('OVERRIDE_REQUIRED');
    expect(openDialog).toHaveBeenCalledTimes(1);
    http.expectNone('/api/v1/sales/3/returns');
  });
});
