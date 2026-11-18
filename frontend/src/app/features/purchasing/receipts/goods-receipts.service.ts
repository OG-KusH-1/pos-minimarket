import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import {
  Observable,
  OperatorFunction,
  catchError,
  forkJoin,
  from,
  map,
  of,
  switchMap,
  throwError,
} from 'rxjs';
import { ApiConfiguration } from '../../../api/api-configuration';
import { get2 } from '../../../api/fn/catalog/get-2';
import { list9 } from '../../../api/fn/catalog/list-9';
import { cancel3 } from '../../../api/fn/purchasing/cancel-3';
import { confirm } from '../../../api/fn/purchasing/confirm';
import { create13 } from '../../../api/fn/purchasing/create-13';
import { getById1 } from '../../../api/fn/purchasing/get-by-id-1';
import { getById2 } from '../../../api/fn/purchasing/get-by-id-2';
import { list1 } from '../../../api/fn/purchasing/list-1';
import { list12, List12$Params } from '../../../api/fn/purchasing/list-12';
import { list8 } from '../../../api/fn/purchasing/list-8';
import { receiptDraft } from '../../../api/fn/purchasing/receipt-draft';
import { update11 } from '../../../api/fn/purchasing/update-11';
import { GoodsReceiptCancelRequest } from '../../../api/models/goods-receipt-cancel-request';
import { GoodsReceiptRequest } from '../../../api/models/goods-receipt-request';
import { GoodsReceiptResponse } from '../../../api/models/goods-receipt-response';
import { PageResponseGoodsReceiptSummary } from '../../../api/models/page-response-goods-receipt-summary';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { PurchaseOrderResponse } from '../../../api/models/purchase-order-response';
import { PurchaseOrderSummary } from '../../../api/models/purchase-order-summary';
import { SupplierSummary } from '../../../api/models/supplier-summary';
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
 * Servicio de acceso a la API de recepciones de mercadería (`API.md` F.2, `HU-PRV-05` a
 * `HU-PRV-08`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las recepciones operan
 * sobre el local activo (`X-Store-Id`); `confirmReceipt` envía `Idempotency-Key` mediante
 * `withIdempotency()`.
 */
@Injectable({ providedIn: 'root' })
export class GoodsReceiptsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista recepciones del local activo con filtros, paginación y ordenamiento. */
  listReceipts(
    params?: List12$Params,
    context?: HttpContext,
  ): Observable<PageResponseGoodsReceiptSummary> {
    return unwrapApiResponse(list12(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de una recepción de mercadería. */
  getReceipt(id: number, context?: HttpContext): Observable<GoodsReceiptResponse> {
    return unwrapApiResponse(getById2(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Crea un borrador de recepción (`DRAFT`), desde OC o sin OC (`purchasing:receipt:without-order`). */
  createReceipt(
    body: GoodsReceiptRequest,
    context?: HttpContext,
  ): Observable<GoodsReceiptResponse> {
    return unwrapApiResponse(create13(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza un borrador de recepción (`DRAFT`) con control optimista (`version`). */
  updateReceipt(
    id: number,
    body: GoodsReceiptRequest,
    context?: HttpContext,
  ): Observable<GoodsReceiptResponse> {
    return unwrapApiResponse(update11(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /**
   * Confirma una recepción (`DRAFT` → `CONFIRMED`) moviendo stock, creando lotes y recalculando
   * el costo promedio ponderado. Marca la petición con `withIdempotency()`.
   */
  confirmReceipt(id: number, context?: HttpContext): Observable<GoodsReceiptResponse> {
    return unwrapApiResponse(
      confirm(this.http, this.apiConfig.rootUrl, { id }, withIdempotency(context)),
    );
  }

  /** Cancela un borrador de recepción (`DRAFT`) indicando el motivo obligatorio. */
  cancelReceipt(
    id: number,
    body: GoodsReceiptCancelRequest,
    context?: HttpContext,
  ): Observable<void> {
    return cancel3(this.http, this.apiConfig.rootUrl, { id, body }, context).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Obtiene el borrador prellenado de recepción pendiente para una orden de compra. */
  getOrderReceiptDraft(
    purchaseOrderId: number,
    context?: HttpContext,
  ): Observable<GoodsReceiptRequest> {
    return unwrapApiResponse(
      receiptDraft(this.http, this.apiConfig.rootUrl, { id: purchaseOrderId }, context),
    );
  }

  /** Obtiene el detalle de una orden de compra asociada para mostrar metadata de sus líneas. */
  getOrder(purchaseOrderId: number, context?: HttpContext): Observable<PurchaseOrderResponse> {
    return unwrapApiResponse(
      getById1(this.http, this.apiConfig.rootUrl, { id: purchaseOrderId }, context),
    );
  }

  /** Lista órdenes de compra en estado `SENT` o `PARTIALLY_RECEIVED` listas para recepcionar. */
  listReceivableOrders(context?: HttpContext): Observable<PurchaseOrderSummary[]> {
    return forkJoin([
      unwrapApiResponse(
        list8(
          this.http,
          this.apiConfig.rootUrl,
          { status: 'SENT', size: 50, sort: ['createdAt,desc'] },
          context,
        ),
      ),
      unwrapApiResponse(
        list8(
          this.http,
          this.apiConfig.rootUrl,
          { status: 'PARTIALLY_RECEIVED', size: 50, sort: ['createdAt,desc'] },
          context,
        ),
      ),
    ]).pipe(map(([sent, partial]) => [...(sent.content ?? []), ...(partial.content ?? [])]));
  }

  /** Lista proveedores activos para filtros o recepciones sin OC. */
  listSuppliers(q?: string, context?: HttpContext): Observable<SupplierSummary[]> {
    return unwrapApiResponse(
      list1(
        this.http,
        this.apiConfig.rootUrl,
        { active: true, size: 100, sort: ['legalName,asc'], q: q || undefined },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }

  /** Busca productos activos del catálogo para recepciones sin OC. */
  searchProducts(q: string, context?: HttpContext): Observable<ProductSummary[]> {
    return unwrapApiResponse(
      list9(
        this.http,
        this.apiConfig.rootUrl,
        { q: q || undefined, active: true, size: 20, sort: ['name,asc'] },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }

  /** Obtiene el detalle de un producto (incluye `lotControlled` y `shelfLifeDays`). */
  getProduct(id: number, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(get2(this.http, this.apiConfig.rootUrl, { id }, context));
  }
}
