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
import { get3 } from '../../api/fn/catalog/get-3';
import { list11 } from '../../api/fn/catalog/list-11';
import { download } from '../../api/fn/files/download';
import { upload } from '../../api/fn/files/upload';
import { list23 } from '../../api/fn/inventory/list-23';
import { listUsers } from '../../api/fn/security/list-users';
import { approve1 } from '../../api/fn/shrinkage/approve-1';
import { create6 } from '../../api/fn/shrinkage/create-6';
import { getById5 } from '../../api/fn/shrinkage/get-by-id-5';
import { list6, List6$Params } from '../../api/fn/shrinkage/list-6';
import { reject1 } from '../../api/fn/shrinkage/reject-1';
import { FileResponse } from '../../api/models/file-response';
import { LotResponse } from '../../api/models/lot-response';
import { PageResponseShrinkageResponse } from '../../api/models/page-response-shrinkage-response';
import { ProductResponse } from '../../api/models/product-response';
import { ProductSummary } from '../../api/models/product-summary';
import { ShrinkageRejectRequest } from '../../api/models/shrinkage-reject-request';
import { ShrinkageRequest } from '../../api/models/shrinkage-request';
import { ShrinkageResponse } from '../../api/models/shrinkage-response';
import { UserSummary } from '../../api/models/user-summary';
import { StrictHttpResponse } from '../../api/strict-http-response';
import { ApiError, isApiError } from '../../core/http/api-error';
import { withIdempotency } from '../../core/http/http-context-tokens';

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
 * Servicio de acceso a la API de mermas (`API.md` F.2, `HU-MER-01..03`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las operaciones de
 * mermas y lotes pertenecen al local activo (`X-Store-Id`), mientras que las consultas auxiliares
 * de catálogo y usuarios son globales (`[G]`).
 */
@Injectable({ providedIn: 'root' })
export class ShrinkageService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista las mermas del local activo con paginación, orden y filtros (`status`, `shrinkageType`, `productId`, `from`, `to`). */
  listShrinkages(
    params?: List6$Params,
    context?: HttpContext,
  ): Observable<PageResponseShrinkageResponse> {
    return unwrapApiResponse(list6(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de una merma del local activo por identificador. */
  getShrinkage(id: number, context?: HttpContext): Observable<ShrinkageResponse> {
    return unwrapApiResponse(getById5(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Registra una merma manual (`POST /api/v1/shrinkages`).
   *
   * Marca automáticamente el contexto con idempotencia (`Idempotency-Key`, ADR-14) si el llamador
   * no proporcionó un contexto propio. Puede quedar `APPROVED` (`autoApproved=true`) o
   * `PENDING_APPROVAL` según el límite del rol.
   */
  createShrinkage(
    body: ShrinkageRequest,
    context: HttpContext = withIdempotency(),
  ): Observable<ShrinkageResponse> {
    return unwrapApiResponse(
      create6(this.http, this.apiConfig.rootUrl, { body }, withIdempotency(context)),
    );
  }

  /**
   * Aprueba una merma pendiente (`POST /api/v1/shrinkages/{id}/approve`).
   *
   * Admite `overrideToken` (`X-Override-Token`) para cubrir aprobaciones que exceden el límite
   * `SHRINKAGE_APPROVE_MAX_AMOUNT` del supervisor.
   */
  approveShrinkage(
    id: number,
    overrideToken?: string,
    context?: HttpContext,
  ): Observable<ShrinkageResponse> {
    return unwrapApiResponse(
      approve1(
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

  /** Rechaza una merma pendiente dejando registro del motivo obligatorio y sin alterar stock. */
  rejectShrinkage(
    id: number,
    body: ShrinkageRejectRequest,
    context?: HttpContext,
  ): Observable<ShrinkageResponse> {
    return unwrapApiResponse(reject1(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Sube un archivo de evidencia en categoría `SHRINKAGE_EVIDENCE` (`POST /api/v1/files`). */
  uploadEvidence(file: File, context?: HttpContext): Observable<FileResponse> {
    return unwrapApiResponse(
      upload(
        this.http,
        this.apiConfig.rootUrl,
        { category: 'SHRINKAGE_EVIDENCE', body: { file } },
        context,
      ),
    );
  }

  /** Descarga el binario de un archivo de evidencia por su identificador (`GET /api/v1/files/{id}/content`). */
  downloadEvidence(fileId: number, context?: HttpContext): Observable<Blob> {
    return download(this.http, this.apiConfig.rootUrl, { id: fileId }, context).pipe(
      map((res) => res.body),
      unwrapApiError(),
    );
  }

  /** Busca productos activos del catálogo (excluyendo `PACK`) para el registro de mermas. */
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

  /** Obtiene la ficha completa de un producto para conocer `lotControlled` y `unitOfMeasure`. */
  getProduct(id: number, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(get3(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Lista lotes con saldo disponible de un producto en el local activo. */
  listProductLots(
    productId: number,
    withStock = true,
    context?: HttpContext,
  ): Observable<LotResponse[]> {
    return unwrapApiResponse(
      list23(
        this.http,
        this.apiConfig.rootUrl,
        {
          productId,
          withStock,
          size: 200,
          sort: ['expiryDate,asc'],
        },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }

  /** Lista usuarios activos del local para seleccionar responsable (requiere `security:user:read`). */
  listStoreUsers(storeId: number | null, context?: HttpContext): Observable<UserSummary[]> {
    return unwrapApiResponse(
      listUsers(
        this.http,
        this.apiConfig.rootUrl,
        {
          active: true,
          ...(storeId !== null ? { storeId } : {}),
          size: 100,
          sort: ['fullName,asc'],
        },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }
}
