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
import { get2 } from '../../../api/fn/catalog/get-2';
import { list9 } from '../../../api/fn/catalog/list-9';
import { list18, List18$Params } from '../../../api/fn/inventory/list-18';
import { list20, List20$Params } from '../../../api/fn/inventory/list-20';
import { suggestFefo, SuggestFefo$Params } from '../../../api/fn/inventory/suggest-fefo';
import { update10 } from '../../../api/fn/inventory/update-10';
import { FefoSuggestionResponse } from '../../../api/models/fefo-suggestion-response';
import { LotResponse } from '../../../api/models/lot-response';
import { LotUpdateRequest } from '../../../api/models/lot-update-request';
import { PageResponseExpiryAlertResponse } from '../../../api/models/page-response-expiry-alert-response';
import { PageResponseLotResponse } from '../../../api/models/page-response-lot-response';
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
 * Servicio de acceso a la API de lotes, alertas de vencimiento y sugerencia FEFO
 * (`API.md` F.2, `HU-INV-08`, `HU-INV-09`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las operaciones de
 * lotes y vencimientos son por local (`X-Store-Id`), mientras que las búsquedas auxiliares de
 * productos son globales (`[G]`).
 */
@Injectable({ providedIn: 'root' })
export class LotsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Lista los lotes del local activo con paginación, orden y filtros (`productId`, `status`,
   * `withStock`, `expiringWithinDays`, `q`).
   */
  listLots(params?: List18$Params, context?: HttpContext): Observable<PageResponseLotResponse> {
    return unwrapApiResponse(list18(this.http, this.apiConfig.rootUrl, params, context));
  }

  /**
   * Corrige el número y/o fecha de vencimiento de un lote exigiendo motivo de auditoría y
   * versión optimista (`version`, `409 CONCURRENT_MODIFICATION`).
   */
  updateLot(id: number, body: LotUpdateRequest, context?: HttpContext): Observable<LotResponse> {
    return unwrapApiResponse(update10(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /**
   * Lista paginada de alertas de vencimiento para lotes con saldo positivo en el local activo
   * (`GET /api/v1/expiry-alerts`).
   */
  listExpiryAlerts(
    params?: List20$Params,
    context?: HttpContext,
  ): Observable<PageResponseExpiryAlertResponse> {
    return unwrapApiResponse(list20(this.http, this.apiConfig.rootUrl, params, context));
  }

  /**
   * Calcula la sugerencia FEFO de consumo por lote para un producto y cantidad en el local activo
   * (`GET /api/v1/lots/fefo`).
   */
  suggestFefo(
    params: SuggestFefo$Params,
    context?: HttpContext,
  ): Observable<FefoSuggestionResponse> {
    return unwrapApiResponse(suggestFefo(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Busca productos activos del catálogo (excluyendo `PACK`) para filtros y simulador FEFO. */
  searchProducts(q: string, context?: HttpContext): Observable<ProductSummary[]> {
    const trimmed = q.trim();
    return unwrapApiResponse(
      list9(
        this.http,
        this.apiConfig.rootUrl,
        {
          q: trimmed || undefined,
          active: true,
          size: 20,
          sort: ['name,asc'],
        },
        context,
      ),
    ).pipe(map((page) => (page.content ?? []).filter((item) => item.saleType !== 'PACK')));
  }

  /** Obtiene la ficha completa de un producto por ID. */
  getProduct(id: number, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(get2(this.http, this.apiConfig.rootUrl, { id }, context));
  }
}
