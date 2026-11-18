import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { buildProblem } from '../auth/auth.testing';
import { ApiError, isApiError } from './api-error';
import { errorInterceptor } from './error.interceptor';
import { withoutErrorNotification } from './http-context-tokens';

describe('errorInterceptor', () => {
  const open = vi.fn();
  let client: HttpClient;
  let http: HttpTestingController;

  beforeEach(() => {
    open.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open } },
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** Dispara un GET que falla con el cuerpo dado y devuelve el error recibido por el suscriptor. */
  function fail(
    body: Record<string, unknown> | string,
    status: number,
    options: { context?: ReturnType<typeof withoutErrorNotification> } = {},
  ) {
    let received: unknown;
    client.get('/api/v1/x', options).subscribe({ error: (e: unknown) => (received = e) });
    http.expectOne('/api/v1/x').flush(body, { status, statusText: 'err' });
    return received;
  }

  it('convierte un ProblemDetail en ApiError con code, traceId y errors[]', () => {
    const error = fail(
      buildProblem(400, 'VALIDATION_ERROR', {
        errors: [{ field: 'name', code: 'NotBlank', message: 'obligatorio' }, { field: 'basura' }],
      }),
      400,
    );

    expect(isApiError(error)).toBe(true);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(400);
    expect(apiError.code).toBe('VALIDATION_ERROR');
    expect(apiError.traceId).toBe('trace-123');
    expect(apiError.message).toBe('detalle de VALIDATION_ERROR');
    expect(apiError.errors).toEqual([{ field: 'name', code: 'NotBlank', message: 'obligatorio' }]);
    expect(apiError.hasFieldErrors).toBe(true);
  });

  it('expone los miembros no estándar (action/context de OVERRIDE_REQUIRED) en extensions', () => {
    const error = fail(
      buildProblem(422, 'OVERRIDE_REQUIRED', {
        action: 'DISCOUNT_ITEM_OVER_LIMIT',
        context: { percent: 20 },
      }),
      422,
    ) as ApiError;

    expect(error.extensions).toEqual({
      action: 'DISCOUNT_ITEM_OVER_LIMIT',
      context: { percent: 20 },
    });
  });

  it('usa el header X-Trace-Id como respaldo cuando el cuerpo no trae traceId', () => {
    let received: unknown;
    client.get('/api/v1/x').subscribe({ error: (err: unknown) => (received = err) });
    http.expectOne('/api/v1/x').flush('<html></html>', {
      status: 502,
      statusText: 'err',
      headers: { 'X-Trace-Id': 'hdr-9' },
    });

    expect((received as ApiError).traceId).toBe('hdr-9');
    expect(open.mock.calls[0]?.[0]).toContain('hdr-9');
  });

  it('el traceId del cuerpo tiene prioridad sobre el header', () => {
    let received: unknown;
    client.get('/api/v1/x').subscribe({ error: (err: unknown) => (received = err) });
    http.expectOne('/api/v1/x').flush(buildProblem(500, 'INTERNAL_ERROR'), {
      status: 500,
      statusText: 'err',
      headers: { 'X-Trace-Id': 'hdr-9' },
    });

    expect((received as ApiError).traceId).toBe('trace-123');
  });

  it('tolera cuerpos que no son ProblemDetail (HTML de un proxy)', () => {
    const error = fail('<html>Bad gateway</html>', 502) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('UNKNOWN_ERROR');
    expect(error.traceId).toBeNull();
    expect(error.errors).toEqual([]);
  });

  it('muestra snackbar en español para un error no manejado', () => {
    fail(buildProblem(403, 'ACCESS_DENIED'), 403);

    expect(open).toHaveBeenCalledOnce();
    expect(open.mock.calls[0]?.[0]).toBe('No tienes permiso para realizar esta acción.');
  });

  it('usa el detail del backend para errores de negocio sin mensaje propio', () => {
    fail(buildProblem(422, 'STOCK_INSUFFICIENT', { detail: 'Stock insuficiente de Leche' }), 422);

    expect(open.mock.calls[0]?.[0]).toBe('Stock insuficiente de Leche');
  });

  it('NO muestra snackbar para VALIDATION_ERROR con errors[]', () => {
    fail(
      buildProblem(400, 'VALIDATION_ERROR', {
        errors: [{ field: 'name', code: 'NotBlank', message: 'obligatorio' }],
      }),
      400,
    );

    expect(open).not.toHaveBeenCalled();
  });

  it('SÍ muestra snackbar para VALIDATION_ERROR sin errors[] (p. ej. Idempotency-Key ausente)', () => {
    fail(buildProblem(400, 'VALIDATION_ERROR', { detail: 'Falta Idempotency-Key' }), 400);

    expect(open).toHaveBeenCalledOnce();
  });

  it('NO muestra snackbar para OVERRIDE_REQUIRED', () => {
    fail(buildProblem(422, 'OVERRIDE_REQUIRED', { action: 'SALE_VOID', context: {} }), 422);

    expect(open).not.toHaveBeenCalled();
  });

  it('incluye el traceId en el mensaje de los 500 y no filtra el detalle interno', () => {
    fail(buildProblem(500, 'INTERNAL_ERROR', { detail: 'NullPointerException en X' }), 500);

    const message = open.mock.calls[0]?.[0] as string;
    expect(message).toContain('trace-123');
    expect(message).toContain('error inesperado');
    expect(message).not.toContain('NullPointer');
  });

  it('500 sin traceId muestra el mensaje genérico', () => {
    fail({ code: 'INTERNAL_ERROR' }, 500);

    expect(open.mock.calls[0]?.[0]).toBe('Ocurrió un error inesperado en el servidor.');
  });

  it('avisa cuando no hay conexión con el servidor', () => {
    let received: unknown;
    client.get('/api/v1/x').subscribe({ error: (e: unknown) => (received = e) });
    http.expectOne('/api/v1/x').error(new ProgressEvent('error'));

    expect((received as ApiError).code).toBe('NETWORK_ERROR');
    expect(open.mock.calls[0]?.[0]).toContain('No se pudo conectar');
  });

  it('respeta SKIP_ERROR_NOTIFICATION pero igualmente entrega un ApiError', () => {
    const error = fail(buildProblem(409, 'DUPLICATED_VALUE'), 409, {
      context: withoutErrorNotification(),
    });

    expect(open).not.toHaveBeenCalled();
    expect(error).toBeInstanceOf(ApiError);
  });

  it('no altera las respuestas exitosas', () => {
    let body: unknown;
    client.get('/api/v1/x').subscribe((value) => (body = value));
    http.expectOne('/api/v1/x').flush({ ok: true });

    expect(body).toEqual({ ok: true });
    expect(open).not.toHaveBeenCalled();
  });
});
