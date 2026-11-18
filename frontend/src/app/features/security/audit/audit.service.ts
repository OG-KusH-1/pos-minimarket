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
import { getById5 } from '../../../api/fn/audit/get-by-id-5';
import { list20, List20$Params } from '../../../api/fn/audit/list-20';
import { verify } from '../../../api/fn/audit/verify';
import { listUsers } from '../../../api/fn/security/list-users';
import { AuditLogResponse } from '../../../api/models/audit-log-response';
import { AuditVerifyRequest } from '../../../api/models/audit-verify-request';
import { AuditVerifyResponse } from '../../../api/models/audit-verify-response';
import { PageResponseAuditLogResponse } from '../../../api/models/page-response-audit-log-response';
import { UserSummary } from '../../../api/models/user-summary';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen`.
 *
 * El cliente generado usa `responseType: 'blob'` cuando el OpenAPI declara `* / *`; si el cuerpo
 * llega como `Blob`, se lee y parsea como JSON; si ya es un objeto, se devuelve tal cual.
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
 * `errorInterceptor` envolvió un `HttpErrorResponse` del cliente generado.
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

/** Desenvuelve respuesta exitosa `Blob` y posibles errores `ProblemDetail` recibidos como `Blob` o texto JSON. */
function unwrapApiResponse<T>(source$: Observable<StrictHttpResponse<T>>): Observable<T> {
  return source$.pipe(
    switchMap((response) => unwrapBody(response.body)),
    unwrapApiError(),
  );
}

/**
 * Servicio de acceso a la API de auditoría inmutable y verificación de cadena SHA-256 (`API.md` F.2).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Todos los endpoints de
 * auditoría son globales (`[G]`), por lo que `storeContextInterceptor` omite `X-Store-Id`.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Consulta paginada de la bitácora de auditoría con filtros y orden en servidor (`HU-SEG-08`). */
  listLogs(
    params?: List20$Params,
    context?: HttpContext,
  ): Observable<PageResponseAuditLogResponse> {
    return unwrapApiResponse(list20(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de un registro de auditoría por su identificador (`HU-SEG-08`). */
  getLog(id: number, context?: HttpContext): Observable<AuditLogResponse> {
    return unwrapApiResponse(getById5(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Verifica la integridad de la cadena de hash SHA-256 en el rango `[fromId, toId]` o en toda la
   * bitácora si ambos se omiten (`HU-SEG-09`, ADR-18).
   */
  verifyChain(body?: AuditVerifyRequest, context?: HttpContext): Observable<AuditVerifyResponse> {
    return unwrapApiResponse(
      verify(this.http, this.apiConfig.rootUrl, { body: body ?? {} }, context),
    );
  }

  /** Lista usuarios para poblar el selector de filtro cuando el actor tiene `security:user:read`. */
  listUsers(context?: HttpContext): Observable<UserSummary[]> {
    return unwrapApiResponse(
      listUsers(this.http, this.apiConfig.rootUrl, { size: 200, sort: ['username,asc'] }, context),
    ).pipe(map((page) => page.content ?? []));
  }
}
