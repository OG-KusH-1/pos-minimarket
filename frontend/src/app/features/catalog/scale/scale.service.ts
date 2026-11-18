import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
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
import { ApiConfiguration } from '../../../api/api-configuration';
import { create5 } from '../../../api/fn/catalog/create-5';
import { list4 } from '../../../api/fn/catalog/list-4';
import { update5 } from '../../../api/fn/catalog/update-5';
import { ScaleBarcodeConfigRequest } from '../../../api/models/scale-barcode-config-request';
import { ScaleBarcodeConfigResponse } from '../../../api/models/scale-barcode-config-response';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen`.
 *
 * El cliente generado usa `responseType: 'blob'` cuando el OpenAPI declara `* /*`; si el cuerpo
 * llega como `Blob`, se lee y parsea como JSON.
 */
function unwrapBody<T>(body: T): Observable<T> {
  if (typeof Blob !== 'undefined' && body instanceof Blob) {
    return from(body.text()).pipe(
      map((text) => (text ? (JSON.parse(text) as T) : (undefined as T))),
    );
  }
  return of(body);
}

/**
 * Convierte errores `Blob` (`ProblemDetail` en respuestas `responseType: 'blob'`) en un
 * {@link ApiError} completo cuando `errorInterceptor` envolvió un `HttpErrorResponse`.
 */
function unwrapApiError<T>(): OperatorFunction<T, T> {
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
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
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

/** Desenvuelve respuesta exitosa `Blob` y posibles errores `ProblemDetail` recibidos como `Blob`. */
function unwrapApiResponse<T>(source$: Observable<StrictHttpResponse<T>>): Observable<T> {
  return source$.pipe(
    switchMap((response) => unwrapBody(response.body)),
    unwrapApiError(),
  );
}

/**
 * Servicio de acceso a la API de configuraciones de códigos de balanza (`API.md` F.2,
 * `HU-CAT-05`, `HU-CFG-07`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Es un recurso global
 * (`[G]`), por lo que `storeContextInterceptor` omite `X-Store-Id` automáticamente.
 */
@Injectable({ providedIn: 'root' })
export class ScaleService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista todas las configuraciones de códigos de balanza registradas. */
  listConfigs(context?: HttpContext): Observable<ScaleBarcodeConfigResponse[]> {
    return unwrapApiResponse(list4(this.http, this.apiConfig.rootUrl, undefined, context));
  }

  /** Crea una nueva configuración de código de balanza EAN-13. */
  createConfig(
    body: ScaleBarcodeConfigRequest,
    context?: HttpContext,
  ): Observable<ScaleBarcodeConfigResponse> {
    return unwrapApiResponse(create5(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza una configuración de código de balanza existente. */
  updateConfig(
    id: number,
    body: ScaleBarcodeConfigRequest,
    context?: HttpContext,
  ): Observable<ScaleBarcodeConfigResponse> {
    return unwrapApiResponse(update5(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }
}
