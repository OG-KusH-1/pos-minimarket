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
import { list9 } from '../../../api/fn/catalog/list-9';
import { alerts } from '../../../api/fn/inventory/alerts';
import { getByStore } from '../../../api/fn/inventory/get-by-store';
import { list12, List12$Params } from '../../../api/fn/inventory/list-12';
import { updateLimits } from '../../../api/fn/inventory/update-limits';
import { CategoryResponse } from '../../../api/models/category-response';
import { PageResponseStockResponse } from '../../../api/models/page-response-stock-response';
import { StockAlertResponse } from '../../../api/models/stock-alert-response';
import { StockByStoreResponse } from '../../../api/models/stock-by-store-response';
import { StockLimitsRequest } from '../../../api/models/stock-limits-request';
import { StockResponse } from '../../../api/models/stock-response';
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
 * Servicio de acceso a la API de stock, alertas y límites por local (`API.md` F.2,
 * `HU-INV-01`, `HU-INV-04`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las operaciones de
 * stock son por local (`X-Store-Id` inyectado automáticamente por `storeContextInterceptor`),
 * mientras que la consulta auxiliar de categorías es global (`[G]`).
 */
@Injectable({ providedIn: 'root' })
export class StockService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Lista el stock del local activo con paginación, orden y filtros (`q`, `categoryId`, `status`,
   * `lotControlled`).
   */
  listStock(params?: List12$Params, context?: HttpContext): Observable<PageResponseStockResponse> {
    return unwrapApiResponse(list12(this.http, this.apiConfig.rootUrl, params, context));
  }

  /**
   * Obtiene las alertas de inventario del local activo: productos bajo mínimo, conteo sin stock
   * y lotes próximos a vencer.
   */
  getAlerts(context?: HttpContext): Observable<StockAlertResponse> {
    return unwrapApiResponse(alerts(this.http, this.apiConfig.rootUrl, undefined, context));
  }

  /**
   * Consulta las existencias de un producto en todos los locales asignados al usuario, junto con
   * la cantidad en tránsito hacia el local activo y el total de empresa.
   */
  getStockByStore(productId: number, context?: HttpContext): Observable<StockByStoreResponse> {
    return unwrapApiResponse(getByStore(this.http, this.apiConfig.rootUrl, { productId }, context));
  }

  /**
   * Actualiza los límites mínimo y máximo de stock de un producto en el local activo (RN-34).
   */
  updateLimits(
    productId: number,
    body: StockLimitsRequest,
    context?: HttpContext,
  ): Observable<StockResponse> {
    return unwrapApiResponse(
      updateLimits(this.http, this.apiConfig.rootUrl, { productId, body }, context),
    );
  }

  /** Obtiene el árbol de categorías para poblar el filtro por categoría. */
  listCategories(context?: HttpContext): Observable<CategoryResponse[]> {
    return unwrapApiResponse(list9(this.http, this.apiConfig.rootUrl, { tree: true }, context));
  }
}
