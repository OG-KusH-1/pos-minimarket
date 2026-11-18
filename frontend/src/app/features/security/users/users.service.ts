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
import { list2 } from '../../../api/fn/organization/list-2';
import { createUser } from '../../../api/fn/security/create-user';
import { disableUser } from '../../../api/fn/security/disable-user';
import { enableUser } from '../../../api/fn/security/enable-user';
import { getUser } from '../../../api/fn/security/get-user';
import { listRoles, ListRoles$Params } from '../../../api/fn/security/list-roles';
import { listSessions } from '../../../api/fn/security/list-sessions';
import { listUsers, ListUsers$Params } from '../../../api/fn/security/list-users';
import { resetPassword } from '../../../api/fn/security/reset-password';
import { resetPin } from '../../../api/fn/security/reset-pin';
import { revokeSessions } from '../../../api/fn/security/revoke-sessions';
import { unlockUser } from '../../../api/fn/security/unlock-user';
import { updateUser } from '../../../api/fn/security/update-user';
import { PageResponseRoleResponse } from '../../../api/models/page-response-role-response';
import { PageResponseUserSummary } from '../../../api/models/page-response-user-summary';
import { SessionResponse } from '../../../api/models/session-response';
import { StoreResponse } from '../../../api/models/store-response';
import { TemporaryPasswordResponse } from '../../../api/models/temporary-password-response';
import { UserCreatedResponse } from '../../../api/models/user-created-response';
import { UserRequest } from '../../../api/models/user-request';
import { UserResponse } from '../../../api/models/user-response';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen`.
 *
 * El cliente generado actualmente usa `responseType: 'blob'` cuando el OpenAPI declara `* / *`;
 * si el cuerpo llega como `Blob`, se lee y parsea como JSON; si ya es un objeto, se devuelve tal cual.
 */
export function unwrapBody<T>(body: T): Observable<T> {
  if (typeof Blob !== 'undefined' && body instanceof Blob) {
    return from(body.text()).pipe(
      map((text) => (text ? (JSON.parse(text) as T) : (undefined as T))),
    );
  }
  return of(body);
}

/**
 * Convierte errores `Blob` (`ProblemDetail` en respuestas `responseType: 'blob'`) en un
 * {@link ApiError} con `code`, `message` y `errors[]` completos para formularios y diálogos.
 */
export function unwrapApiError<T>(): OperatorFunction<T, T> {
  return catchError((err: unknown) => {
    const httpErr =
      isApiError(err) && err.cause instanceof HttpErrorResponse
        ? err.cause
        : err instanceof HttpErrorResponse
          ? err
          : null;

    if (httpErr && typeof Blob !== 'undefined' && httpErr.error instanceof Blob) {
      return from(httpErr.error.text()).pipe(
        switchMap((text) => {
          try {
            const parsed: unknown = text ? JSON.parse(text) : null;
            if (parsed && typeof parsed === 'object') {
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
        }),
      );
    }
    return throwError(() => err);
  });
}

/** Desenvuelve cuerpo exitoso y errores `ProblemDetail` recibidos como `Blob`. */
export function unwrapApiResponse<T>(source$: Observable<StrictHttpResponse<T>>): Observable<T> {
  return source$.pipe(
    switchMap((response) => unwrapBody(response.body)),
    unwrapApiError(),
  );
}

/** Desenvuelve respuestas sin contenido (`204`) conservando la conversión de errores `Blob`. */
export function unwrapVoidApiResponse(
  source$: Observable<StrictHttpResponse<void>>,
): Observable<void> {
  return source$.pipe(
    map(() => undefined),
    unwrapApiError(),
  );
}

/**
 * Servicio de acceso a la API de usuarios y sesiones (`API.md` F.2).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api` para mantener el contrato
 * sincronizado con OpenAPI. Las peticiones de usuarios y roles son globales (`[G]`), por lo que
 * `storeContextInterceptor` omite `X-Store-Id` automáticamente.
 */
@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista usuarios paginados según filtros (`q`, `roleId`, `storeId`, `active`, `locked`). */
  listUsers(params?: ListUsers$Params, context?: HttpContext): Observable<PageResponseUserSummary> {
    return unwrapApiResponse(listUsers(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de un usuario por ID. */
  getUser(id: number): Observable<UserResponse> {
    return unwrapApiResponse(getUser(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Crea un usuario y devuelve su contraseña temporal (se muestra una sola vez). */
  createUser(body: UserRequest, context?: HttpContext): Observable<UserCreatedResponse> {
    return unwrapApiResponse(createUser(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza datos, roles y locales asignados de un usuario. */
  updateUser(id: number, body: UserRequest, context?: HttpContext): Observable<UserResponse> {
    return unwrapApiResponse(updateUser(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Desactiva un usuario y revoca sus sesiones activas. */
  disableUser(id: number): Observable<void> {
    return unwrapVoidApiResponse(disableUser(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Reactiva un usuario previamente desactivado. */
  enableUser(id: number): Observable<void> {
    return unwrapVoidApiResponse(enableUser(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Genera una nueva contraseña temporal para el usuario. */
  resetPassword(id: number): Observable<TemporaryPasswordResponse> {
    return unwrapApiResponse(resetPassword(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Restablece el PIN del usuario y levanta un eventual bloqueo por intentos fallidos. */
  resetPin(id: number): Observable<void> {
    return unwrapVoidApiResponse(resetPin(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Desbloquea la cuenta y el PIN de un usuario. */
  unlockUser(id: number): Observable<void> {
    return unwrapVoidApiResponse(unlockUser(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Lista las sesiones activas de un usuario. */
  listSessions(id: number): Observable<SessionResponse[]> {
    return unwrapApiResponse(listSessions(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Revoca todas las sesiones activas de un usuario. */
  revokeSessions(id: number): Observable<void> {
    return unwrapVoidApiResponse(revokeSessions(this.http, this.apiConfig.rootUrl, { id }));
  }

  /** Obtiene los roles disponibles para poblar filtros y selector de asignación. */
  listRoles(params: ListRoles$Params = { size: 200 }): Observable<PageResponseRoleResponse> {
    return unwrapApiResponse(listRoles(this.http, this.apiConfig.rootUrl, params));
  }

  /** Obtiene los locales activos accesibles para el usuario actual. */
  listStores(): Observable<StoreResponse[]> {
    return unwrapApiResponse(
      list2(this.http, this.apiConfig.rootUrl, { active: true, size: 200 }),
    ).pipe(map((page) => page.content ?? []));
  }
}
