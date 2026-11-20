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
import { ApiConfiguration } from '../../api/api-configuration';
import { list11 } from '../../api/fn/catalog/list-11';
import { list16 } from '../../api/fn/catalog/list-16';
import { delete$ } from '../../api/fn/replenishment/delete';
import { generateDrafts } from '../../api/fn/replenishment/generate-drafts';
import { getRun } from '../../api/fn/replenishment/get-run';
import { list } from '../../api/fn/replenishment/list';
import { listLines1, ListLines1$Params } from '../../api/fn/replenishment/list-lines-1';
import { listRuns, ListRuns$Params } from '../../api/fn/replenishment/list-runs';
import { run } from '../../api/fn/replenishment/run';
import { updateLine } from '../../api/fn/replenishment/update-line';
import { upsert } from '../../api/fn/replenishment/upsert';
import { listByProduct, ListByProduct$Params } from '../../api/fn/purchasing/list-by-product';
import { CategoryResponse } from '../../api/models/category-response';
import { GenerateDraftsRequest } from '../../api/models/generate-drafts-request';
import { GeneratedOrdersResponse } from '../../api/models/generated-orders-response';
import { PageResponseReplenishmentLineResponse } from '../../api/models/page-response-replenishment-line-response';
import { PageResponseReplenishmentRunResponse } from '../../api/models/page-response-replenishment-run-response';
import { ProductSummary } from '../../api/models/product-summary';
import { ProductSupplierResponse } from '../../api/models/product-supplier-response';
import { ReplenishmentLineResponse } from '../../api/models/replenishment-line-response';
import { ReplenishmentLineUpdateRequest } from '../../api/models/replenishment-line-update-request';
import { ReplenishmentParamsRequest } from '../../api/models/replenishment-params-request';
import { ReplenishmentParamsResponse } from '../../api/models/replenishment-params-response';
import { ReplenishmentRunResponse } from '../../api/models/replenishment-run-response';
import { RunRequest } from '../../api/models/run-request';
import { StrictHttpResponse } from '../../api/strict-http-response';
import { ApiError, isApiError } from '../../core/http/api-error';
import { IDEMPOTENT, withIdempotency } from '../../core/http/http-context-tokens';
import { PageResponse } from '../../shared/models/page-response';

/** Normaliza la página generada por `ng-openapi-gen` al contrato estricto `PageResponse<T>`. */
function normalizePage<T>(page: {
  content?: T[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
  sort?: string[];
}): PageResponse<T> {
  return {
    content: page.content ?? [],
    page: page.page ?? 0,
    size: page.size ?? 20,
    totalElements: page.totalElements ?? 0,
    totalPages: page.totalPages ?? 0,
    sort: page.sort ?? [],
  };
}

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
 * Servicio de acceso a la API de reabastecimiento (`API.md` F.2, `HU-PRV-09`, `HU-PRV-10`,
 * `HU-PRV-11`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las corridas y líneas
 * operan sobre el local activo (`X-Store-Id`), las filas de parámetros usan el alcance que la
 * propia fila declara y las consultas auxiliares (categorías, productos, candidatos
 * producto-proveedor) son globales (`[G]`).
 */
@Injectable({ providedIn: 'root' })
export class ReplenishmentService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista las filas de parámetros de reabastecimiento, opcionalmente filtradas por alcance. */
  listParams(
    scope?: ReplenishmentParamsRequest['scope'],
    context?: HttpContext,
  ): Observable<ReplenishmentParamsResponse[]> {
    return unwrapApiResponse(list(this.http, this.apiConfig.rootUrl, { scope }, context));
  }

  /** Crea o actualiza (upsert) una fila de parámetros por alcance y clave (UQ de B.11). */
  upsertParams(
    body: ReplenishmentParamsRequest,
    context?: HttpContext,
  ): Observable<ReplenishmentParamsResponse> {
    return unwrapApiResponse(upsert(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /**
   * Elimina una fila de parámetros. El servidor rechaza borrar el GLOBAL (409 `ENTITY_IN_USE`):
   * los componentes deben presentar ese error con un mensaje claro.
   */
  deleteParam(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(delete$(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Ejecuta una corrida síncrona de reabastecimiento sobre el local activo con clave de
   * idempotencia ({@link withIdempotency}): la operación es [I] en `API.md` F.2.
   *
   * @param body Opcional; sin cuerpo el backend usa la fecha de corte por defecto (hoy de negocio).
   */
  executeRun(body?: RunRequest, context?: HttpContext): Observable<ReplenishmentRunResponse> {
    const idempotentContext = context?.get(IDEMPOTENT) ? context : withIdempotency(context);
    return unwrapApiResponse(run(this.http, this.apiConfig.rootUrl, { body }, idempotentContext));
  }

  /** Lista las corridas de reabastecimiento del local activo con paginación y orden. */
  listRuns(
    params?: ListRuns$Params,
    context?: HttpContext,
  ): Observable<PageResponse<ReplenishmentRunResponse>> {
    return unwrapApiResponse<PageResponseReplenishmentRunResponse>(
      listRuns(this.http, this.apiConfig.rootUrl, params, context),
    ).pipe(map((page) => normalizePage(page)));
  }

  /** Obtiene la cabecera de una corrida (estado, conteos y ventana). */
  getRun(id: number, context?: HttpContext): Observable<ReplenishmentRunResponse> {
    return unwrapApiResponse(getRun(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Lista las líneas de sugerencia de una corrida con filtros de estado, proveedor, flag y categoría. */
  listLines(
    params: ListLines1$Params,
    context?: HttpContext,
  ): Observable<PageResponse<ReplenishmentLineResponse>> {
    return unwrapApiResponse<PageResponseReplenishmentLineResponse>(
      listLines1(this.http, this.apiConfig.rootUrl, params, context),
    ).pipe(map((page) => normalizePage(page)));
  }

  /**
   * Edita una línea de sugerencia (unidades finales, proveedor elegido, `ACCEPTED`/`DISCARDED`).
   * Si la línea ya está incluida en una OC el servidor responde 409 `INVALID_STATE_TRANSITION`.
   */
  updateLine(
    id: number,
    body: ReplenishmentLineUpdateRequest,
    context?: HttpContext,
  ): Observable<ReplenishmentLineResponse> {
    return unwrapApiResponse(updateLine(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /**
   * Genera borradores de OC (`DRAFT`, `origin=REPLENISHMENT`) desde las líneas de una corrida,
   * una OC por proveedor (`PLAN.md` C.5), con clave de idempotencia: la operación es [I].
   *
   * @param body Sin `lineIds` (o vacío) el backend aplica el comportamiento por defecto: agrupa
   *   las líneas `SUGGESTED`/`ACCEPTED` por proveedor.
   */
  generateDrafts(
    id: number,
    body?: GenerateDraftsRequest,
    context?: HttpContext,
  ): Observable<GeneratedOrdersResponse> {
    const idempotentContext = context?.get(IDEMPOTENT) ? context : withIdempotency(context);
    return unwrapApiResponse(
      generateDrafts(this.http, this.apiConfig.rootUrl, { id, body }, idempotentContext),
    );
  }

  /** Lista los candidatos activos producto-proveedor de un producto (MOQ, unidad de compra, lead time). */
  listProductSuppliers(
    params: ListByProduct$Params,
    context?: HttpContext,
  ): Observable<ProductSupplierResponse[]> {
    return unwrapApiResponse(listByProduct(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Lista el árbol de categorías para el selector de alcance CATEGORY en los parámetros. */
  listCategories(context?: HttpContext): Observable<CategoryResponse[]> {
    return unwrapApiResponse(list16(this.http, this.apiConfig.rootUrl, { tree: true }, context));
  }

  /** Busca productos activos por texto (SKU, código de barras o nombre) para el alcance PRODUCT. */
  searchProducts(q: string, context?: HttpContext): Observable<ProductSummary[]> {
    return unwrapApiResponse(
      list11(
        this.http,
        this.apiConfig.rootUrl,
        { q: q || undefined, active: true, size: 20, sort: ['name,asc'] },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }
}
