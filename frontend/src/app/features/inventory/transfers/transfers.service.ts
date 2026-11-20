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
import { cancel } from '../../../api/fn/inventory/cancel';
import { create4 } from '../../../api/fn/inventory/create-4';
import { getById } from '../../../api/fn/inventory/get-by-id';
import { list23 } from '../../../api/fn/inventory/list-23';
import { list4, List4$Params } from '../../../api/fn/inventory/list-4';
import { receive } from '../../../api/fn/inventory/receive';
import { send } from '../../../api/fn/inventory/send';
import { suggestFefo } from '../../../api/fn/inventory/suggest-fefo';
import { update4 } from '../../../api/fn/inventory/update-4';
import { list3 } from '../../../api/fn/organization/list-3';
import { CancelTransferRequest } from '../../../api/models/cancel-transfer-request';
import { FefoSuggestionResponse } from '../../../api/models/fefo-suggestion-response';
import { LotResponse } from '../../../api/models/lot-response';
import { PageResponseStockTransferResponse } from '../../../api/models/page-response-stock-transfer-response';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';
import { StockTransferRequest } from '../../../api/models/stock-transfer-request';
import { StockTransferResponse } from '../../../api/models/stock-transfer-response';
import { StoreResponse } from '../../../api/models/store-response';
import { TransferReceiveRequest } from '../../../api/models/transfer-receive-request';
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
 * Servicio de acceso a la API de transferencias de stock entre locales (`API.md` F.2, `HU-INV-07`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las operaciones de
 * transferencias y lotes operan sobre el local activo (`X-Store-Id`), mientras que las consultas
 * auxiliares de locales y productos son globales (`[G]`).
 */
@Injectable({ providedIn: 'root' })
export class TransfersService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Lista las transferencias donde participa el local activo (como origen o destino) con filtros
   * de dirección (`in`/`out`), estado, rango de fechas, paginación y ordenamiento.
   */
  listTransfers(
    params?: List4$Params,
    context?: HttpContext,
  ): Observable<PageResponseStockTransferResponse> {
    return unwrapApiResponse(list4(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de una transferencia visible para el local activo. */
  getTransfer(id: number, context?: HttpContext): Observable<StockTransferResponse> {
    return unwrapApiResponse(getById(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Crea un borrador de transferencia (`DRAFT`) desde el local activo hacia el local destino.
   * Marca la petición con clave de idempotencia (`withIdempotency`) para evitar duplicados en
   * reintentos.
   */
  createTransfer(
    body: StockTransferRequest,
    context?: HttpContext,
  ): Observable<StockTransferResponse> {
    return unwrapApiResponse(
      create4(this.http, this.apiConfig.rootUrl, { body }, withIdempotency(context)),
    );
  }

  /** Actualiza un borrador de transferencia (`DRAFT`) existente desde el local de origen. */
  updateTransfer(
    id: number,
    body: StockTransferRequest,
    context?: HttpContext,
  ): Observable<StockTransferResponse> {
    return unwrapApiResponse(update4(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /**
   * Despacha una transferencia en borrador desde el local de origen (`TRANSFER_OUT`, FEFO
   * automático si no se especificó lote). Usa `Idempotency-Key` obligatorio (`[I]`).
   */
  sendTransfer(id: number, context?: HttpContext): Observable<StockTransferResponse> {
    return unwrapApiResponse(
      send(this.http, this.apiConfig.rootUrl, { id }, withIdempotency(context)),
    );
  }

  /**
   * Recepciona en el local destino una transferencia en tránsito (`SENT`), registrando cantidades
   * recibidas y motivos de diferencia (`TRANSFER_IN`). Usa `Idempotency-Key` obligatorio (`[I]`).
   */
  receiveTransfer(
    id: number,
    body: TransferReceiveRequest,
    context?: HttpContext,
  ): Observable<StockTransferResponse> {
    return unwrapApiResponse(
      receive(this.http, this.apiConfig.rootUrl, { id, body }, withIdempotency(context)),
    );
  }

  /** Cancela un borrador de transferencia (`DRAFT`) desde el local de origen con motivo obligatorio. */
  cancelTransfer(id: number, body: CancelTransferRequest, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(cancel(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Lista los locales activos disponibles para seleccionar el destino de la transferencia. */
  listStores(context?: HttpContext): Observable<StoreResponse[]> {
    return unwrapApiResponse(
      list3(
        this.http,
        this.apiConfig.rootUrl,
        { active: true, size: 200, sort: ['name,asc'] },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }

  /** Busca productos activos del catálogo por nombre o SKU para agregar líneas al borrador. */
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
    ).pipe(map((page) => page.content ?? []));
  }

  /** Obtiene la ficha completa del producto (incluye `lotControlled` y `unitOfMeasure`). */
  getProduct(id: number, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(get3(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Lista los lotes con stock positivo de un producto en el local activo (orden FEFO). */
  listProductLots(productId: number, context?: HttpContext): Observable<LotResponse[]> {
    return unwrapApiResponse(
      list23(
        this.http,
        this.apiConfig.rootUrl,
        {
          productId,
          withStock: true,
          size: 100,
          sort: ['expiryDate,asc', 'id,asc'],
        },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }

  /** Consulta la asignación FEFO sugerida para un producto y cantidad en el local activo. */
  suggestFefo(
    productId: number,
    quantity: number,
    context?: HttpContext,
  ): Observable<FefoSuggestionResponse> {
    return unwrapApiResponse(
      suggestFefo(this.http, this.apiConfig.rootUrl, { productId, quantity }, context),
    );
  }
}
