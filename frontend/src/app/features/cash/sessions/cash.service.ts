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
import { approveDifference } from '../../../api/fn/cash/approve-difference';
import { close } from '../../../api/fn/cash/close';
import { closingReceipt$Json } from '../../../api/fn/cash/closing-receipt-json';
import { closingReceipt$Pdf } from '../../../api/fn/cash/closing-receipt-pdf';
import { current } from '../../../api/fn/cash/current';
import { get4 } from '../../../api/fn/cash/get-4';
import { list10, List10$Params } from '../../../api/fn/cash/list-10';
import { listMovements } from '../../../api/fn/cash/list-movements';
import { open } from '../../../api/fn/cash/open';
import { registerMovement } from '../../../api/fn/cash/register-movement';
import { summary } from '../../../api/fn/cash/summary';
import { effective } from '../../../api/fn/organization/effective';
import { listByStore } from '../../../api/fn/organization/list-by-store';
import { listUsers } from '../../../api/fn/security/list-users';
import { CashClosingReceiptResponse } from '../../../api/models/cash-closing-receipt-response';
import { CashMovementRequest } from '../../../api/models/cash-movement-request';
import { CashMovementResponse } from '../../../api/models/cash-movement-response';
import { CashSessionResponse } from '../../../api/models/cash-session-response';
import { CashSessionSummaryResponse } from '../../../api/models/cash-session-summary-response';
import { CloseCashSessionRequest } from '../../../api/models/close-cash-session-request';
import { OpenCashSessionRequest } from '../../../api/models/open-cash-session-request';
import { PageResponseCashSessionResponse } from '../../../api/models/page-response-cash-session-response';
import { TerminalResponse } from '../../../api/models/terminal-response';
import { UserSummary } from '../../../api/models/user-summary';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';
import { withIdempotency } from '../../../core/http/http-context-tokens';
import { generateUuid } from '../../../core/http/uuid';
import { CashSettings, extractCashSettings } from './cash.models';

/** Opciones para registrar un movimiento de efectivo con idempotencia y override opcional. */
export interface RegisterMovementOptions {
  readonly idempotencyKey?: string;
  readonly overrideToken?: string;
  readonly context?: HttpContext;
}

/** Opciones para cerrar un turno con token de supervisor opcional. */
export interface CloseSessionOptions {
  readonly overrideToken?: string;
  readonly context?: HttpContext;
}

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen`.
 *
 * El cliente generado usa `responseType: 'blob'` cuando el OpenAPI declara `* / *`; si el cuerpo
 * llega como `Blob`, se lee y parsea como JSON (o `undefined` cuando el cuerpo está vacío, como en
 * `204 No Content`).
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
 * Convierte errores `Blob` o texto JSON (`ProblemDetail`) recibidos en `ApiError.cause` (cuando
 * `errorInterceptor` envolvió un `HttpErrorResponse` de `ng-openapi-gen`) en un {@link ApiError}
 * completo preservando `code`, `message`, `errors` y `extensions` (`action`, `context`).
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
 * Servicio de acceso a la API de caja y turnos (`API.md` F.2, `HU-CAJ-01`..`HU-CAJ-07`).
 *
 * Delega todas las peticiones en las funciones generadas de `src/app/api/fn/cash` (y auxiliares de
 * organización/seguridad para filtros y tolerancia). Las cabeceras `X-Store-Id` y `X-Terminal-Id`
 * son inyectadas automáticamente por `storeContextInterceptor`.
 */
@Injectable({ providedIn: 'root' })
export class CashService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Abre un turno de caja en la terminal activa (`POST /api/v1/cash-sessions`, `[I][T]`). */
  openSession(
    body: OpenCashSessionRequest,
    context?: HttpContext,
  ): Observable<CashSessionResponse> {
    const idempotentContext = withIdempotency(context ?? new HttpContext());
    return unwrapApiResponse(open(this.http, this.apiConfig.rootUrl, { body }, idempotentContext));
  }

  /**
   * Consulta el turno abierto actual de la terminal activa (`GET /api/v1/cash-sessions/current`,
   * `[T]`). Devuelve `null` si el servidor responde `204 No Content`.
   */
  getCurrentSession(context?: HttpContext): Observable<CashSessionResponse | null> {
    return unwrapApiResponse(current(this.http, this.apiConfig.rootUrl, undefined, context)).pipe(
      map((session) => session ?? null),
    );
  }

  /** Lista el historial paginado de turnos del local activo (`GET /api/v1/cash-sessions`). */
  listSessions(
    params?: List10$Params,
    context?: HttpContext,
  ): Observable<PageResponseCashSessionResponse> {
    return unwrapApiResponse(list10(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle de un turno por identificador (`GET /api/v1/cash-sessions/{id}`). */
  getSession(id: number, context?: HttpContext): Observable<CashSessionResponse> {
    return unwrapApiResponse(get4(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Calcula el resumen financiero esperado del turno (`GET /api/v1/cash-sessions/{id}/summary`). */
  getSummary(id: number, context?: HttpContext): Observable<CashSessionSummaryResponse> {
    return unwrapApiResponse(summary(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Lista los movimientos manuales de efectivo de un turno (`GET /api/v1/cash-sessions/{id}/movements`). */
  listMovements(id: number, context?: HttpContext): Observable<CashMovementResponse[]> {
    return unwrapApiResponse(
      listMovements(this.http, this.apiConfig.rootUrl, { id }, context),
    ).pipe(map((items) => items ?? []));
  }

  /**
   * Registra un ingreso (`CASH_IN`) o retiro (`CASH_OUT`) en un turno abierto
   * (`POST /api/v1/cash-sessions/{id}/movements`, `[I][O:CASH_OUT_OVER_LIMIT]`).
   */
  registerMovement(
    id: number,
    body: CashMovementRequest,
    options?: RegisterMovementOptions,
  ): Observable<CashMovementResponse> {
    const idempotencyKey = options?.idempotencyKey ?? generateUuid();
    return unwrapApiResponse(
      registerMovement(
        this.http,
        this.apiConfig.rootUrl,
        {
          id,
          'Idempotency-Key': idempotencyKey,
          ...(options?.overrideToken ? { 'X-Override-Token': options.overrideToken } : {}),
          body,
        },
        options?.context,
      ),
    );
  }

  /**
   * Cierra un turno registrando el arqueo por medio de pago
   * (`POST /api/v1/cash-sessions/{id}/close`, `[I][O:CASH_DIFFERENCE_APPROVAL]`).
   */
  closeSession(
    id: number,
    body: CloseCashSessionRequest,
    options?: CloseSessionOptions,
  ): Observable<CashSessionResponse> {
    const idempotentContext = withIdempotency(options?.context ?? new HttpContext());
    return unwrapApiResponse(
      close(
        this.http,
        this.apiConfig.rootUrl,
        {
          id,
          ...(options?.overrideToken ? { 'X-Override-Token': options.overrideToken } : {}),
          body,
        },
        idempotentContext,
      ),
    );
  }

  /**
   * Aprueba la diferencia de cierre de un turno cerrado (`POST /api/v1/cash-sessions/{id}/approve-difference`,
   * exige SOD `RN-23`).
   */
  approveDifference(id: number, context?: HttpContext): Observable<CashSessionResponse> {
    return unwrapApiResponse(approveDifference(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Obtiene el comprobante de cierre estructurado en JSON (`?format=json`). */
  getClosingReceiptJson(id: number, context?: HttpContext): Observable<CashClosingReceiptResponse> {
    return unwrapApiResponse(
      closingReceipt$Json(this.http, this.apiConfig.rootUrl, { id, format: 'json' }, context),
    );
  }

  /** Descarga el comprobante de cierre en PDF (`?format=pdf`). */
  downloadClosingReceiptPdf(id: number, context?: HttpContext): Observable<Blob> {
    return closingReceipt$Pdf(
      this.http,
      this.apiConfig.rootUrl,
      { id, format: 'pdf' },
      context,
    ).pipe(
      map((response) => response.body),
      unwrapApiError(),
    );
  }

  /** Obtiene los parámetros efectivos de caja del local (`cash.difference_tolerance`, `cash.default_opening_amount`). */
  getCashSettings(context?: HttpContext): Observable<CashSettings> {
    return unwrapApiResponse(effective(this.http, this.apiConfig.rootUrl, undefined, context)).pipe(
      map((response) =>
        extractCashSettings(response?.values as Readonly<Record<string, unknown>> | undefined),
      ),
    );
  }

  /** Lista las terminales del local para el filtro del historial. */
  listTerminals(storeId: number, context?: HttpContext): Observable<TerminalResponse[]> {
    return unwrapApiResponse(
      listByStore(this.http, this.apiConfig.rootUrl, { storeId }, context),
    ).pipe(map((items) => items ?? []));
  }

  /** Lista usuarios activos del local para el filtro de cajero (requiere `security:user:read`). */
  listStoreUsers(storeId: number, context?: HttpContext): Observable<UserSummary[]> {
    return unwrapApiResponse(
      listUsers(this.http, this.apiConfig.rootUrl, { storeId, active: true, size: 200 }, context),
    ).pipe(map((page) => page?.content ?? []));
  }
}
