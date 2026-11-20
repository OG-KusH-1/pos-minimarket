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
import { createReturn } from '../../../api/fn/sale-returns/create-return';
import { returnable } from '../../../api/fn/sale-returns/returnable';
import { voidSale } from '../../../api/fn/sale-returns/void-sale';
import { getById6 } from '../../../api/fn/sales/get-by-id-6';
import { getByNumber } from '../../../api/fn/sales/get-by-number';
import { SaleResponse } from '../../../api/models/sale-response';
import { SaleReturnRequest } from '../../../api/models/sale-return-request';
import { SaleReturnResponse } from '../../../api/models/sale-return-response';
import { SaleReturnableLinesResponse } from '../../../api/models/sale-returnable-lines-response';
import { SaleVoidRequest } from '../../../api/models/sale-void-request';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';
import { generateUuid } from '../../../core/http/uuid';

/** Opciones de una operación de escritura (anulación o devolución) con idempotencia y override. */
export interface ReturnOperationOptions {
  /**
   * Clave de `Idempotency-Key` de la operación. Debe crearla el llamador ANTES de envolver la
   * petición en `withSupervisorOverride`, para que el reintento con `X-Override-Token` reenvíe
   * la misma clave (ADR-14, L-04). Por defecto se genera un UUID nuevo.
   */
  readonly idempotencyKey?: string;
  /** Token de supervisor (`X-Override-Token`) obtenido en el reintento de `OVERRIDE_REQUIRED`. */
  readonly overrideToken?: string;
  /** `HttpContext` adicional de la petición (p. ej. `withoutErrorNotification()`). */
  readonly context?: HttpContext;
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
 * Servicio de la pantalla POS de anulación y devolución de ventas (`HU-POS-14`, `HU-POS-15`,
 * `API.md` F.2 endpoints de `sales`/`sale-returns` y flujo 4 de F.4).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api/fn`. Los permisos y el
 * alcance por cajero (`NOT_FOUND` para ventas ajenas) los resuelve el backend; los overrides
 * `SALE_VOID` y `SALE_RETURN` los orquesta la UI con `SupervisorOverrideService`.
 */
@Injectable({ providedIn: 'root' })
export class ReturnsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Busca una venta por su folio/número (`GET /sales/by-number/{number}`). Es el paso 1 del
   * flujo 4 de F.4. Con alcance de cajero, una venta ajena responde 404 `NOT_FOUND`.
   */
  getSaleByNumber(number: string, context?: HttpContext): Observable<SaleResponse> {
    return unwrapApiResponse(getByNumber(this.http, this.apiConfig.rootUrl, { number }, context));
  }

  /**
   * Carga las líneas devolvibles de una venta (`GET /sales/{id}/returnable`): cantidad máxima
   * real por línea, reembolso unitario, `voidAllowed` (RN-12) y `returnDeadline` (RN-13).
   */
  getReturnableLines(
    saleId: number,
    context?: HttpContext,
  ): Observable<SaleReturnableLinesResponse> {
    return unwrapApiResponse(
      returnable(this.http, this.apiConfig.rootUrl, { id: saleId }, context),
    );
  }

  /**
   * Recarga el detalle de una venta por su id (`GET /sales/{id}`). Se usa después de anular o
   * devolver para reflejar el nuevo estado (`VOIDED`/`PARTIALLY_RETURNED`) y las cantidades.
   */
  getSaleById(saleId: number, context?: HttpContext): Observable<SaleResponse> {
    return unwrapApiResponse(getById6(this.http, this.apiConfig.rootUrl, { id: saleId }, context));
  }

  /**
   * Anula totalmente una venta (`POST /sales/{id}/void`, `[I][T][O:SALE_VOID]`).
   *
   * Reingresa todo el stock (SALE_VOID), reversa los pagos y audita; responde con el mismo
   * `SaleReturnResponse` que una devolución (`returnType=VOID`).
   */
  voidSale(
    saleId: number,
    body: SaleVoidRequest,
    options?: ReturnOperationOptions,
  ): Observable<SaleReturnResponse> {
    return unwrapApiResponse(
      voidSale(
        this.http,
        this.apiConfig.rootUrl,
        this.buildParams(saleId, body, options),
        options?.context,
      ),
    );
  }

  /**
   * Registra una devolución parcial o total (`POST /sales/{id}/returns`, `[I][T][O:SALE_RETURN]`).
   *
   * Cada línea lleva su `quantity ≤ returnableQuantity` y su flag `restock`; si `refunds` se
   * omite, el backend reembolsa por los mismos medios de la venta en proporción (F.3).
   */
  createReturn(
    saleId: number,
    body: SaleReturnRequest,
    options?: ReturnOperationOptions,
  ): Observable<SaleReturnResponse> {
    return unwrapApiResponse(
      createReturn(
        this.http,
        this.apiConfig.rootUrl,
        this.buildParams(saleId, body, options),
        options?.context,
      ),
    );
  }

  /**
   * Arma los parámetros comunes de las operaciones de escritura: path `{id}`, cabecera
   * `Idempotency-Key` (la misma en ambos intentos del override) y `X-Override-Token` opcional.
   */
  private buildParams<TBody>(
    saleId: number,
    body: TBody,
    options?: ReturnOperationOptions,
  ): { id: number; 'Idempotency-Key': string; 'X-Override-Token'?: string; body: TBody } {
    return {
      id: saleId,
      'Idempotency-Key': options?.idempotencyKey ?? generateUuid(),
      ...(options?.overrideToken ? { 'X-Override-Token': options.overrideToken } : {}),
      body,
    };
  }
}
