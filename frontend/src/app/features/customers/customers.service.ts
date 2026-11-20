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
import { account, Account$Params } from '../../api/fn/customers/account';
import { activate6 } from '../../api/fn/customers/activate-6';
import { create15 } from '../../api/fn/customers/create-15';
import { deactivate6 } from '../../api/fn/customers/deactivate-6';
import { get4 } from '../../api/fn/customers/get-4';
import { list15, List15$Params } from '../../api/fn/customers/list-15';
import { registerAdjustment } from '../../api/fn/customers/register-adjustment';
import { registerPayment } from '../../api/fn/customers/register-payment';
import { update13 } from '../../api/fn/customers/update-13';
import { updateCredit } from '../../api/fn/customers/update-credit';
import { CustomerAccountMovementResponse } from '../../api/models/customer-account-movement-response';
import { CustomerAccountStatementResponse } from '../../api/models/customer-account-statement-response';
import { CustomerAdjustmentRequest } from '../../api/models/customer-adjustment-request';
import { CustomerCreditSettingsRequest } from '../../api/models/customer-credit-settings-request';
import { CustomerPaymentRequest } from '../../api/models/customer-payment-request';
import { CustomerRequest } from '../../api/models/customer-request';
import { CustomerResponse } from '../../api/models/customer-response';
import { PageResponseCustomerResponse } from '../../api/models/page-response-customer-response';
import { StrictHttpResponse } from '../../api/strict-http-response';
import { ApiError, isApiError } from '../../core/http/api-error';
import { generateUuid } from '../../core/http/uuid';

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

/** Parámetros de consulta de la cartola de cuenta corriente (`GET /customers/{id}/account`). */
export type CustomerAccountQuery = Account$Params;

/** Opciones del registro de abono (`POST /customers/{id}/payments`, [I]). */
export interface CustomerPaymentOptions {
  /**
   * Clave de idempotencia (ADR-14). Si se omite se genera un UUID; pásala solo cuando el
   * llamador quiera controlar la deduplicación (p. ej. reintentos del mismo formulario).
   */
  readonly idempotencyKey?: string;
}

/** Opciones del registro de ajuste de cuenta (`POST /customers/{id}/adjustments`, [I]). */
export interface CustomerAdjustmentOptions {
  /** Clave de idempotencia (ADR-14); si se omite se genera un UUID. */
  readonly idempotencyKey?: string;
}

/**
 * Servicio de acceso a clientes y cuenta corriente (fiado) (`API.md` F.2, `HU-POS-16`, `HU-POS-17`).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api` bajo `/api/v1/customers`.
 * Los POST idempotentes ([I]) de abono y ajuste envían `Idempotency-Key` explícita (el OpenAPI
 * declara la cabecera, igual que en movimientos de caja); el resto de operaciones no la necesita.
 */
@Injectable({ providedIn: 'root' })
export class CustomersService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Lista clientes paginados y filtrados (`GET /api/v1/customers`, [G]).
   *
   * Filtros: `q` (RUT o nombre), `withDebt` (solo con deuda), `creditEnabled` y `active`.
   */
  listCustomers(
    params?: List15$Params,
    context?: HttpContext,
  ): Observable<PageResponseCustomerResponse> {
    return unwrapApiResponse(list15(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de un cliente, incluida su `version` para edición optimista. */
  getCustomer(id: number, context?: HttpContext): Observable<CustomerResponse> {
    return unwrapApiResponse(get4(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Crea un cliente nuevo (`RUT_INVALID`, `DUPLICATED_VALUE`). */
  createCustomer(body: CustomerRequest, context?: HttpContext): Observable<CustomerResponse> {
    return unwrapApiResponse(create15(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza un cliente enviando su `version` para concurrencia optimista. */
  updateCustomer(
    id: number,
    body: CustomerRequest,
    context?: HttpContext,
  ): Observable<CustomerResponse> {
    return unwrapApiResponse(update13(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Activa un cliente inactivo (`POST /customers/{id}/activate`). */
  activateCustomer(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(activate6(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Desactiva un cliente activo; falla con `ENTITY_IN_USE` si mantiene deuda mayor que cero
   * (`POST /customers/{id}/deactivate`).
   */
  deactivateCustomer(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(deactivate6(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Configura el fiado de un cliente: cupo y habilitación (`PUT /customers/{id}/credit`).
   *
   * Requiere `customers:credit:manage-limit`; un cupo sobre el máximo asignable responde
   * `CREDIT_LIMIT_EXCEEDED`.
   */
  updateCreditSettings(
    id: number,
    body: CustomerCreditSettingsRequest,
    context?: HttpContext,
  ): Observable<CustomerResponse> {
    return unwrapApiResponse(
      updateCredit(this.http, this.apiConfig.rootUrl, { id, body }, context),
    );
  }

  /**
   * Obtiene el estado de cuenta y la cartola paginada de movimientos
   * (`GET /customers/{id}/account`, filtros `from`/`to`).
   */
  getAccount(
    params: Account$Params,
    context?: HttpContext,
  ): Observable<CustomerAccountStatementResponse> {
    return unwrapApiResponse(account(this.http, this.apiConfig.rootUrl, params, context));
  }

  /**
   * Registra un abono en cuenta corriente (`POST /customers/{id}/payments`, [I]; solo CASH o
   * TRANSFER). Un monto mayor que la deuda responde `PAYMENT_EXCEEDS_DEBT`; un abono en efectivo
   * suma al esperado del turno de caja abierto.
   */
  registerPayment(
    id: number,
    body: CustomerPaymentRequest,
    options?: CustomerPaymentOptions,
    context?: HttpContext,
  ): Observable<CustomerAccountMovementResponse> {
    return unwrapApiResponse(
      registerPayment(
        this.http,
        this.apiConfig.rootUrl,
        {
          id,
          'Idempotency-Key': options?.idempotencyKey ?? generateUuid(),
          body,
        },
        context,
      ),
    );
  }

  /**
   * Registra un ajuste manual de deuda (`POST /customers/{id}/adjustments`, [I]; positivo aumenta
   * la deuda, negativo la reduce). `abs(amount)` debe respetar `CREDIT_ADJUSTMENT_MAX_AMOUNT` del
   * rol; si lo excede el backend responde `CREDIT_LIMIT_EXCEEDED` (no `OVERRIDE_REQUIRED`).
   */
  registerAdjustment(
    id: number,
    body: CustomerAdjustmentRequest,
    options?: CustomerAdjustmentOptions,
    context?: HttpContext,
  ): Observable<CustomerAccountMovementResponse> {
    return unwrapApiResponse(
      registerAdjustment(
        this.http,
        this.apiConfig.rootUrl,
        {
          id,
          'Idempotency-Key': options?.idempotencyKey ?? generateUuid(),
          body,
        },
        context,
      ),
    );
  }
}
