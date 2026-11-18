import { HttpClient, HttpErrorResponse, HttpRequest } from '@angular/common/http';
import { WritableSignal } from '@angular/core';
import { AbstractControl, FormGroup, ValidationErrors, ValidatorFn } from '@angular/forms';
import {
  Observable,
  OperatorFunction,
  catchError,
  from,
  map,
  of,
  switchMap,
  throwError,
} from 'rxjs';
import { StrictHttpResponse } from '../../api/strict-http-response';
import { ApiError, ErrorCodes, isApiError } from '../../core/http/api-error';

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen`.
 *
 * El cliente generado usa `responseType: 'blob'` cuando el OpenAPI declara `* / *`;
 * si el cuerpo llega como `Blob`, se lee y parsea como JSON; si ya es un objeto, se devuelve tal cual.
 */
export function unwrapBody<T>(body: T): Observable<T> {
  if (typeof Blob !== 'undefined' && body instanceof Blob) {
    return from(body.text()).pipe(
      map((text) => (text ? (JSON.parse(text) as T) : (undefined as T))),
    );
  }
  return of(body);
}

/**
 * Convierte errores `Blob` (ProblemDetail en respuestas `responseType: 'blob'`) en un {@link ApiError}
 * con `code`, `message` y `errors[]` completos para formularios y diálogos.
 */
export function unwrapApiError<T>(): OperatorFunction<T, T> {
  return catchError((err: unknown) => {
    const httpErr =
      isApiError(err) && err.cause instanceof HttpErrorResponse
        ? err.cause
        : err instanceof HttpErrorResponse
          ? err
          : null;

    if (httpErr && typeof Blob !== 'undefined' && httpErr.error instanceof Blob) {
      return from(httpErr.error.text()).pipe(
        switchMap((text) => {
          try {
            const parsed: unknown = text ? JSON.parse(text) : null;
            if (parsed && typeof parsed === 'object') {
              const cloned = new HttpErrorResponse({
                error: parsed,
                headers: httpErr.headers,
                status: httpErr.status,
                statusText: httpErr.statusText,
                url: httpErr.url ?? undefined,
              });
              return throwError(() => ApiError.fromHttpError(cloned));
            }
          } catch {
            // Cuerpo no JSON: se propaga el error original.
          }
          return throwError(() => err);
        }),
      );
    }
    return throwError(() => err);
  });
}

/**
 * Desenvuelve tanto el cuerpo exitoso como posibles errores `ProblemDetail` recibidos como `Blob`
 * desde las funciones generadas por `ng-openapi-gen`.
 */
export function unwrapApiResponse<T>(source$: Observable<StrictHttpResponse<T>>): Observable<T> {
  return source$.pipe(
    switchMap((response) => unwrapBody(response.body)),
    unwrapApiError(),
  );
}

/**
 * Envuelve `HttpClient` para fijar `X-Store-Id` cuando una pantalla administrativa opera sobre un
 * local específico distinto del activo en `StoreContextService`.
 *
 * `storeContextInterceptor` respeta el header cuando la petición ya lo incluye.
 */
export function httpForStore(http: HttpClient, storeId?: number | null): HttpClient {
  if (storeId === undefined || storeId === null) {
    return http;
  }
  return {
    request: (req: HttpRequest<unknown>) =>
      http.request(req.clone({ setHeaders: { 'X-Store-Id': String(storeId) } })),
  } as unknown as HttpClient;
}

/**
 * Valida que un campo numérico sea un número entero (ignora valores vacíos para combinarse con
 * `Validators.required`).
 */
export const integerValidator: ValidatorFn = (
  control: AbstractControl,
): ValidationErrors | null => {
  const value: unknown = control.value;
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const num = typeof value === 'number' ? value : Number(value);
  return Number.isInteger(num) ? null : { integer: true };
};

/**
 * Traslada errores devueltos por la API (`ApiError`) a los controles de un formulario o al mensaje
 * general de error del servidor.
 */
export function applyFormApiErrors(
  form: FormGroup,
  error: unknown,
  serverError: WritableSignal<string | null>,
): void {
  if (!isApiError(error)) {
    serverError.set('No se pudieron guardar los cambios. Inténtalo nuevamente.');
    return;
  }
  if (error.hasFieldErrors) {
    let mapped = false;
    for (const fieldErr of error.errors) {
      const control = form.get(fieldErr.field);
      if (control) {
        control.setErrors({ server: fieldErr.message });
        control.markAsTouched();
        mapped = true;
      }
    }
    if (!mapped) {
      serverError.set(error.message);
    }
    return;
  }
  if (error.code === 'RUT_INVALID') {
    const rutControl = form.get('rut');
    if (rutControl) {
      rutControl.setErrors({ server: error.message });
      rutControl.markAsTouched();
      return;
    }
  }
  if (error.code === ErrorCodes.validation) {
    serverError.set(error.message);
    return;
  }
  serverError.set(error.message);
}
