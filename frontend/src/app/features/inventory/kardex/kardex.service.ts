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
import { get3 } from '../../../api/fn/catalog/get-3';
import { list11 } from '../../../api/fn/catalog/list-11';
import { list24, List24$Params } from '../../../api/fn/inventory/list-24';
import { PageResponseStockMovementResponse } from '../../../api/models/page-response-stock-movement-response';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen`.
 *
 * El cliente generado usa `responseType: 'blob'` cuando el OpenAPI declara `* / *`; si el cuerpo
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
          const obj = parsed as Record<string, unknown>;
          const normalized =
            typeof obj['detail'] === 'string' || typeof obj['title'] === 'string'
              ? obj
              : typeof obj['message'] === 'string'
                ? { ...obj, detail: obj['message'] }
                : obj;
          const cloned = new HttpErrorResponse({
            error: normalized,
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
 * Servicio de acceso de solo lectura al kardex de inventario (`API.md` F.2, `HU-INV-02`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las consultas de kardex
 * son por local (`X-Store-Id`), mientras que la búsqueda auxiliar de productos es global (`[G]`).
 */
@Injectable({ providedIn: 'root' })
export class KardexService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Lista los movimientos de stock de un producto en el local activo (`productId` obligatorio).
   */
  listMovements(
    params: List24$Params,
    context?: HttpContext,
  ): Observable<PageResponseStockMovementResponse> {
    return unwrapApiResponse(list24(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Busca productos del catálogo por nombre o SKU para el selector de producto del kardex. */
  searchProducts(q: string, context?: HttpContext): Observable<ProductSummary[]> {
    const trimmed = q.trim();
    return unwrapApiResponse(
      list11(
        this.http,
        this.apiConfig.rootUrl,
        {
          q: trimmed || undefined,
          size: 20,
          sort: ['name,asc'],
        },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }

  /** Obtiene el detalle de un producto por ID (útil al entrar por `?productId=...`). */
  getProduct(id: number, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(get3(this.http, this.apiConfig.rootUrl, { id }, context));
  }
}
