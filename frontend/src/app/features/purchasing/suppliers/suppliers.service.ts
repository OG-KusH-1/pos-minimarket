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
import { list9, List9$Params } from '../../../api/fn/catalog/list-9';
import { activate1 } from '../../../api/fn/purchasing/activate-1';
import { activate4 } from '../../../api/fn/purchasing/activate-4';
import { create1 } from '../../../api/fn/purchasing/create-1';
import { create11 } from '../../../api/fn/purchasing/create-11';
import { deactivate1 } from '../../../api/fn/purchasing/deactivate-1';
import { deactivate4 } from '../../../api/fn/purchasing/deactivate-4';
import { get } from '../../../api/fn/purchasing/get';
import { list1, List1$Params } from '../../../api/fn/purchasing/list-1';
import { listBySupplier, ListBySupplier$Params } from '../../../api/fn/purchasing/list-by-supplier';
import { markPreferred } from '../../../api/fn/purchasing/mark-preferred';
import { update2 } from '../../../api/fn/purchasing/update-2';
import { update9 } from '../../../api/fn/purchasing/update-9';
import { PageResponseProductSummary } from '../../../api/models/page-response-product-summary';
import { PageResponseProductSupplierResponse } from '../../../api/models/page-response-product-supplier-response';
import { PageResponseSupplierSummary } from '../../../api/models/page-response-supplier-summary';
import { ProductSupplierRequest } from '../../../api/models/product-supplier-request';
import { ProductSupplierResponse } from '../../../api/models/product-supplier-response';
import { SupplierRequest } from '../../../api/models/supplier-request';
import { SupplierResponse } from '../../../api/models/supplier-response';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen` cuando llega como `Blob`.
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
 * Servicio de acceso a proveedores y asociaciones producto-proveedor (`HU-PRV-01`, `HU-PRV-02`).
 *
 * Consume exclusivamente los endpoints generados por OpenAPI bajo `/api/v1/suppliers`,
 * `/api/v1/product-suppliers` y `/api/v1/products`.
 */
@Injectable({ providedIn: 'root' })
export class SuppliersService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista proveedores paginados y filtrados por texto (RUT o nombre) y estado. */
  listSuppliers(
    params?: List1$Params,
    context?: HttpContext,
  ): Observable<PageResponseSupplierSummary> {
    return unwrapApiResponse(list1(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de un proveedor, incluyendo `version` para edición optimista. */
  getSupplier(id: number, context?: HttpContext): Observable<SupplierResponse> {
    return unwrapApiResponse(get(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Crea un nuevo proveedor. */
  createSupplier(body: SupplierRequest, context?: HttpContext): Observable<SupplierResponse> {
    return unwrapApiResponse(create1(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza un proveedor existente enviando su `version` para concurrencia optimista. */
  updateSupplier(
    id: number,
    body: SupplierRequest,
    context?: HttpContext,
  ): Observable<SupplierResponse> {
    return unwrapApiResponse(update2(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Activa un proveedor inactivo. */
  activateSupplier(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(activate1(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Desactiva un proveedor activo. */
  deactivateSupplier(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(deactivate1(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Lista las asociaciones producto-proveedor de un proveedor dado. */
  listSupplierProducts(
    params: ListBySupplier$Params,
    context?: HttpContext,
  ): Observable<PageResponseProductSupplierResponse> {
    return unwrapApiResponse(listBySupplier(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Crea una nueva asociación producto-proveedor (rechaza productos tipo `PACK`). */
  createProductSupplier(
    body: ProductSupplierRequest,
    context?: HttpContext,
  ): Observable<ProductSupplierResponse> {
    return unwrapApiResponse(create11(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza una asociación producto-proveedor existente. */
  updateProductSupplier(
    id: number,
    body: ProductSupplierRequest,
    context?: HttpContext,
  ): Observable<ProductSupplierResponse> {
    return unwrapApiResponse(update9(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Activa una asociación producto-proveedor. */
  activateProductSupplier(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(activate4(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Desactiva una asociación producto-proveedor. */
  deactivateProductSupplier(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(deactivate4(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Marca una asociación como proveedor preferido único del producto. */
  markPreferredProductSupplier(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(markPreferred(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Busca productos del catálogo para asociar a un proveedor. */
  searchProducts(
    params?: List9$Params,
    context?: HttpContext,
  ): Observable<PageResponseProductSummary> {
    return unwrapApiResponse(list9(this.http, this.apiConfig.rootUrl, params, context));
  }
}
