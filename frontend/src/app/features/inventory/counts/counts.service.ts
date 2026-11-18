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
import { list6 } from '../../../api/fn/catalog/list-6';
import { list9 } from '../../../api/fn/catalog/list-9';
import { approve1 } from '../../../api/fn/inventory/approve-1';
import { cancel1 } from '../../../api/fn/inventory/cancel-1';
import { create8 } from '../../../api/fn/inventory/create-8';
import { get3 } from '../../../api/fn/inventory/get-3';
import { list8, List8$Params } from '../../../api/fn/inventory/list-8';
import { listLines, ListLines$Params } from '../../../api/fn/inventory/list-lines';
import { recordLines } from '../../../api/fn/inventory/record-lines';
import { reject1 } from '../../../api/fn/inventory/reject-1';
import { submit } from '../../../api/fn/inventory/submit';
import { CategoryResponse } from '../../../api/models/category-response';
import { CountLineResponse } from '../../../api/models/count-line-response';
import { CountLinesRequest } from '../../../api/models/count-lines-request';
import { InventoryCountCancelRequest } from '../../../api/models/inventory-count-cancel-request';
import { InventoryCountRejectRequest } from '../../../api/models/inventory-count-reject-request';
import { InventoryCountRequest } from '../../../api/models/inventory-count-request';
import { InventoryCountResponse } from '../../../api/models/inventory-count-response';
import { PageResponseCountLineResponse } from '../../../api/models/page-response-count-line-response';
import { PageResponseInventoryCountResponse } from '../../../api/models/page-response-inventory-count-response';
import { PageResponseProductSummary } from '../../../api/models/page-response-product-summary';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';
import { IDEMPOTENT, withIdempotency } from '../../../core/http/http-context-tokens';

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
 * Servicio de acceso a la API de tomas físicas de inventario (`API.md` F.2, `HU-INV-06`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las operaciones de
 * tomas de inventario actúan sobre el local activo (`X-Store-Id` inyectado automáticamente por
 * `storeContextInterceptor`), mientras que categorías y productos son consultas globales (`[G]`).
 */
@Injectable({ providedIn: 'root' })
export class CountsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista paginada de tomas de inventario del local activo con filtros opcionales. */
  listCounts(
    params?: List8$Params,
    context?: HttpContext,
  ): Observable<PageResponseInventoryCountResponse> {
    return unwrapApiResponse(list8(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene la cabecera y progreso actualizado de una toma de inventario. */
  getCount(id: number, context?: HttpContext): Observable<InventoryCountResponse> {
    return unwrapApiResponse(get3(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Inicia una nueva toma física (`FULL` o `CYCLIC`) aplicando clave de idempotencia mediante
   * {@link withIdempotency}.
   */
  createCount(
    body: InventoryCountRequest,
    context?: HttpContext,
  ): Observable<InventoryCountResponse> {
    const idempotentContext = context?.get(IDEMPOTENT) ? context : withIdempotency(context);
    return unwrapApiResponse(
      create8(this.http, this.apiConfig.rootUrl, { body }, idempotentContext),
    );
  }

  /** Lista paginada de líneas de conteo filtrable por estado contado (`counted`) y búsqueda (`q`). */
  listCountLines(
    params: ListLines$Params,
    context?: HttpContext,
  ): Observable<PageResponseCountLineResponse> {
    return unwrapApiResponse(listLines(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Registra o corrige cantidades contadas en lote (1..500 líneas) en una toma `OPEN`. */
  recordCountLines(
    id: number,
    body: CountLinesRequest,
    context?: HttpContext,
  ): Observable<CountLineResponse[]> {
    return unwrapApiResponse(recordLines(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Envía a revisión (`OPEN -> SUBMITTED`) una toma con todas sus líneas contadas. */
  submitCount(id: number, context?: HttpContext): Observable<InventoryCountResponse> {
    return unwrapApiResponse(submit(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Aprueba una toma `SUBMITTED`, valida segregación de funciones (SoD) y aplica los deltas. */
  approveCount(id: number, context?: HttpContext): Observable<InventoryCountResponse> {
    return unwrapApiResponse(approve1(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Rechaza una toma `SUBMITTED` devolviéndola a estado `OPEN` para reconteo. */
  rejectCount(
    id: number,
    body: InventoryCountRejectRequest,
    context?: HttpContext,
  ): Observable<InventoryCountResponse> {
    return unwrapApiResponse(reject1(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Cancela una toma `OPEN` o `SUBMITTED` liberando el alcance abierto. */
  cancelCount(
    id: number,
    body: InventoryCountCancelRequest,
    context?: HttpContext,
  ): Observable<void> {
    return unwrapApiResponse(cancel1(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Obtiene el árbol de categorías para seleccionar el alcance de una toma cíclica. */
  listCategories(context?: HttpContext): Observable<CategoryResponse[]> {
    return unwrapApiResponse(list9(this.http, this.apiConfig.rootUrl, { tree: true }, context));
  }

  /** Consulta una muestra resumida de productos activos de una categoría para previsualizar el alcance. */
  listProductsByCategory(
    categoryId: number,
    context?: HttpContext,
  ): Observable<PageResponseProductSummary> {
    return unwrapApiResponse(
      list6(
        this.http,
        this.apiConfig.rootUrl,
        { categoryId, active: true, page: 0, size: 5, sort: ['name,asc'] },
        context,
      ),
    );
  }
}
