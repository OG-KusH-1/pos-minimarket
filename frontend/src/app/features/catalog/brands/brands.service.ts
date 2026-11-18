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
import { activate4 } from '../../../api/fn/catalog/activate-4';
import { create10 } from '../../../api/fn/catalog/create-10';
import { deactivate4 } from '../../../api/fn/catalog/deactivate-4';
import { list11, List11$Params } from '../../../api/fn/catalog/list-11';
import { update10 } from '../../../api/fn/catalog/update-10';
import { BrandRequest } from '../../../api/models/brand-request';
import { BrandResponse } from '../../../api/models/brand-response';
import { PageResponseBrandResponse } from '../../../api/models/page-response-brand-response';
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
 * Convierte errores `Blob` o texto JSON (`ProblemDetail`) en un {@link ApiError} completo cuando
 * `errorInterceptor` envolvió un `HttpErrorResponse` de `ng-openapi-gen`.
 */
function unwrapApiError<T>(): OperatorFunction<T, T> {
  return catchError((err: unknown) => {
    const httpErr =
      isApiError(err) && err.cause instanceof HttpErrorResponse
        ? err.cause
        : err instanceof HttpErrorResponse
          ? err
          : null;

    if (!httpErr) {
      return throwError(() => err);
    }

    const rebuildFromText = (text: string): Observable<never> => {
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
    };

    if (typeof Blob !== 'undefined' && httpErr.error instanceof Blob) {
      return from(httpErr.error.text()).pipe(switchMap(rebuildFromText));
    }
    if (typeof httpErr.error === 'string' && httpErr.error.trim().startsWith('{')) {
      return rebuildFromText(httpErr.error);
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
 * Servicio de acceso a la API de marcas (`API.md` F.2, `HU-CAT-12`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Es un recurso global
 * (`[G]`), por lo que `storeContextInterceptor` omite `X-Store-Id` automáticamente.
 */
@Injectable({ providedIn: 'root' })
export class BrandsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista marcas paginadas según filtros (`q`, `active`) y criterios de orden. */
  listBrands(params?: List11$Params, context?: HttpContext): Observable<PageResponseBrandResponse> {
    return unwrapApiResponse(list11(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Crea una nueva marca. */
  createBrand(body: BrandRequest, context?: HttpContext): Observable<BrandResponse> {
    return unwrapApiResponse(create10(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza el nombre de una marca existente. */
  updateBrand(id: number, body: BrandRequest, context?: HttpContext): Observable<BrandResponse> {
    return unwrapApiResponse(update10(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Desactiva una marca. */
  deactivateBrand(id: number): Observable<void> {
    return deactivate4(this.http, this.apiConfig.rootUrl, { id }).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Reactiva una marca previamente desactivada. */
  activateBrand(id: number): Observable<void> {
    return activate4(this.http, this.apiConfig.rootUrl, { id }).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }
}
