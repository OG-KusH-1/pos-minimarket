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
import { approve } from '../../../api/fn/inventory/approve';
import { create5 } from '../../../api/fn/inventory/create-5';
import { getById4 } from '../../../api/fn/inventory/get-by-id-4';
import { list23 } from '../../../api/fn/inventory/list-23';
import { list5, List5$Params } from '../../../api/fn/inventory/list-5';
import { reject } from '../../../api/fn/inventory/reject';
import { LotResponse } from '../../../api/models/lot-response';
import { PageResponseStockAdjustmentResponse } from '../../../api/models/page-response-stock-adjustment-response';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { StockAdjustmentRejectRequest } from '../../../api/models/stock-adjustment-reject-request';
import { StockAdjustmentRequest } from '../../../api/models/stock-adjustment-request';
import { StockAdjustmentResponse } from '../../../api/models/stock-adjustment-response';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';
import { withIdempotency } from '../../../core/http/http-context-tokens';

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
 * Servicio de acceso a la API de ajustes manuales de stock (`API.md` F.2, `HU-INV-05`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las operaciones de
 * ajuste y consulta de lotes pertenecen al local activo (`X-Store-Id`), mientras que las consultas
 * auxiliares de catálogo son globales (`[G]`).
 */
@Injectable({ providedIn: 'root' })
export class AdjustmentsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista los ajustes de stock del local activo con paginación, orden y filtros (`status`, `from`, `to`). */
  listAdjustments(
    params?: List5$Params,
    context?: HttpContext,
  ): Observable<PageResponseStockAdjustmentResponse> {
    return unwrapApiResponse(list5(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de un ajuste de stock del local activo por identificador. */
  getAdjustment(id: number, context?: HttpContext): Observable<StockAdjustmentResponse> {
    return unwrapApiResponse(getById4(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Crea una nueva solicitud de ajuste manual en estado `PENDING_APPROVAL`.
   *
   * Marca automáticamente el contexto con idempotencia (`Idempotency-Key`, ADR-14) si el llamador
   * no proporcionó un contexto propio.
   */
  createAdjustment(
    body: StockAdjustmentRequest,
    context: HttpContext = withIdempotency(),
  ): Observable<StockAdjustmentResponse> {
    return unwrapApiResponse(
      create5(this.http, this.apiConfig.rootUrl, { body }, withIdempotency(context)),
    );
  }

  /**
   * Aprueba un ajuste pendiente aplicando sus movimientos de stock y kardex.
   *
   * Admite `overrideToken` (`X-Override-Token`) para cubrir aprobaciones que exceden el límite
   * `ADJUSTMENT_APPROVE_MAX_AMOUNT` del supervisor.
   */
  approveAdjustment(
    id: number,
    overrideToken?: string,
    context?: HttpContext,
  ): Observable<StockAdjustmentResponse> {
    return unwrapApiResponse(
      approve(
        this.http,
        this.apiConfig.rootUrl,
        {
          id,
          ...(overrideToken ? { 'X-Override-Token': overrideToken } : {}),
        },
        context,
      ),
    );
  }

  /** Rechaza un ajuste pendiente dejando registro del motivo obligatorio y sin alterar stock. */
  rejectAdjustment(
    id: number,
    body: StockAdjustmentRejectRequest,
    context?: HttpContext,
  ): Observable<StockAdjustmentResponse> {
    return unwrapApiResponse(reject(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Busca productos activos del catálogo (excluyendo `PACK`) para el alta de líneas de ajuste. */
  searchProducts(q: string, context?: HttpContext): Observable<ProductSummary[]> {
    const trimmed = q.trim();
    return unwrapApiResponse(
      list11(
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

  /** Obtiene la ficha completa de un producto para conocer `lotControlled`, `shelfLifeDays` y unidad. */
  getProduct(id: number, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(get3(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Lista lotes de un producto en el local activo para seleccionar lote en líneas de ajuste. */
  listProductLots(
    productId: number,
    withStock?: boolean,
    context?: HttpContext,
  ): Observable<LotResponse[]> {
    return unwrapApiResponse(
      list23(
        this.http,
        this.apiConfig.rootUrl,
        {
          productId,
          ...(withStock !== undefined ? { withStock } : {}),
          size: 200,
          sort: ['expiryDate,asc'],
        },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }
}
