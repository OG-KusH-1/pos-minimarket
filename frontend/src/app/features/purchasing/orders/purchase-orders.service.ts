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
import { cancel1 } from '../../../api/fn/purchasing/cancel-1';
import { create9 } from '../../../api/fn/purchasing/create-9';
import { getById1 } from '../../../api/fn/purchasing/get-by-id-1';
import { list1 } from '../../../api/fn/purchasing/list-1';
import { list8, List8$Params } from '../../../api/fn/purchasing/list-8';
import { listBySupplier } from '../../../api/fn/purchasing/list-by-supplier';
import { pdf } from '../../../api/fn/purchasing/pdf';
import { receiptDraft } from '../../../api/fn/purchasing/receipt-draft';
import { send1 } from '../../../api/fn/purchasing/send-1';
import { update7 } from '../../../api/fn/purchasing/update-7';
import { GoodsReceiptRequest } from '../../../api/models/goods-receipt-request';
import { PageResponsePurchaseOrderSummary } from '../../../api/models/page-response-purchase-order-summary';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { ProductSupplierResponse } from '../../../api/models/product-supplier-response';
import { PurchaseOrderCancelRequest } from '../../../api/models/purchase-order-cancel-request';
import { PurchaseOrderRequest } from '../../../api/models/purchase-order-request';
import { PurchaseOrderResponse } from '../../../api/models/purchase-order-response';
import { SendPurchaseOrderRequest } from '../../../api/models/send-purchase-order-request';
import { SupplierSummary } from '../../../api/models/supplier-summary';
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
 * Servicio de acceso a la API de órdenes de compra (`API.md` F.2, `HU-PRV-03`, `HU-PRV-04`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las órdenes operan
 * sobre el local activo (`X-Store-Id`), mientras que las consultas auxiliares de proveedores y
 * productos son globales (`[G]`).
 */
@Injectable({ providedIn: 'root' })
export class PurchaseOrdersService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista órdenes de compra del local activo con filtros, paginación y ordenamiento. */
  listOrders(
    params?: List8$Params,
    context?: HttpContext,
  ): Observable<PageResponsePurchaseOrderSummary> {
    return unwrapApiResponse(list8(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de una orden de compra. */
  getOrder(id: number, context?: HttpContext): Observable<PurchaseOrderResponse> {
    return unwrapApiResponse(getById1(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Crea un borrador de orden de compra (`DRAFT`) en el local activo. */
  createOrder(
    body: PurchaseOrderRequest,
    context?: HttpContext,
  ): Observable<PurchaseOrderResponse> {
    return unwrapApiResponse(create9(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza un borrador de orden de compra (`DRAFT`) con control optimista (`version`). */
  updateOrder(
    id: number,
    body: PurchaseOrderRequest,
    context?: HttpContext,
  ): Observable<PurchaseOrderResponse> {
    return unwrapApiResponse(update7(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Aprueba y envía una orden de compra (`DRAFT` → `SENT`), con opción de correo al proveedor. */
  sendOrder(
    id: number,
    body: SendPurchaseOrderRequest,
    context?: HttpContext,
  ): Observable<PurchaseOrderResponse> {
    return unwrapApiResponse(send1(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Cancela una orden de compra indicando el motivo obligatorio. */
  cancelOrder(
    id: number,
    body: PurchaseOrderCancelRequest,
    context?: HttpContext,
  ): Observable<PurchaseOrderResponse> {
    return unwrapApiResponse(cancel1(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Descarga el comprobante PDF de una orden de compra (`application/pdf`). */
  getOrderPdf(id: number, context?: HttpContext): Observable<Blob> {
    return pdf(this.http, this.apiConfig.rootUrl, { id }, context).pipe(
      map((response) => {
        const body: unknown = response.body;
        if (body instanceof Blob) {
          return body;
        }
        return new Blob([typeof body === 'string' ? body : ''], { type: 'application/pdf' });
      }),
      unwrapApiError(),
    );
  }

  /** Obtiene el borrador prellenado de recepción pendiente para una orden de compra. */
  getReceiptDraft(id: number, context?: HttpContext): Observable<GoodsReceiptRequest> {
    return unwrapApiResponse(receiptDraft(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Lista proveedores activos para selección en filtros o formularios de OC. */
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

  /** Lista productos asociados a un proveedor con su unidad de compra y costo neto. */
  listSupplierProducts(
    supplierId: number,
    q?: string,
    context?: HttpContext,
  ): Observable<ProductSupplierResponse[]> {
    return unwrapApiResponse(
      listBySupplier(
        this.http,
        this.apiConfig.rootUrl,
        { id: supplierId, active: true, size: 100, q: q || undefined },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }

  /** Busca productos activos en el catálogo por texto (SKU, código de barras o nombre). */
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

  /** Obtiene el detalle de un producto del catálogo. */
  getProduct(id: number, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(get2(this.http, this.apiConfig.rootUrl, { id }, context));
  }
}
