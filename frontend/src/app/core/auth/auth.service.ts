import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Observable,
  catchError,
  finalize,
  firstValueFrom,
  map,
  of,
  shareReplay,
  tap,
  throwError,
  timeout,
} from 'rxjs';
import { AUTH_BASE_PATH } from '../http/api-url';
import { withoutErrorNotification } from '../http/http-context-tokens';
import { StoreContextService } from '../store-context/store-context.service';
import type {
  LoginRequest,
  MeResponse,
  PinLoginRequest,
  TokenResponse,
  TokenScope,
} from './auth.models';

/** Espera máxima de la restauración al arrancar: un backend colgado no debe bloquear la app. */
const RESTORE_TIMEOUT_MS = 8000;

/**
 * Sesión del usuario: access token en memoria, usuario con sus permisos y refresh silencioso.
 *
 * Seguridad (ADR-20):
 * - El access token vive SOLO en un signal privado. Nunca se escribe en `localStorage`,
 *   `sessionStorage` ni cookies accesibles por JS, para que un XSS no pueda robarlo del
 *   almacenamiento. Al recargar la página se pierde y se recupera con {@link restoreSession}.
 * - El refresh token es una cookie `HttpOnly` que gestiona el navegador; este servicio nunca la
 *   ve. `authInterceptor` agrega `withCredentials` solo a `/api/v1/auth/*`.
 *
 * Refresh single-flight: {@link refresh} comparte una única petición en vuelo. Si varias
 * peticiones reciben 401 a la vez, todas esperan el mismo refresh (imprescindible porque el
 * refresh token se ROTA en cada uso: un segundo refresh con el token ya usado se interpretaría
 * como reutilización y revocaría la familia completa, RN-32).
 *
 * Los DTO son provisionales (ver `auth.models.ts`, tarea T27). Este servicio no navega: las
 * redirecciones las hacen los guards, el interceptor y las pantallas.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly storeContext = inject(StoreContextService);

  private readonly _accessToken = signal<string | null>(null);
  private readonly _user = signal<MeResponse | null>(null);
  private readonly _scope = signal<TokenScope | null>(null);
  private readonly _mustChangePassword = signal(false);
  /** Refresh en curso compartido entre llamadores; `null` si no hay ninguno. */
  private refreshInFlight: Observable<string> | null = null;

  /** Access token vigente (solo para `authInterceptor`; no persistir ni registrar). */
  readonly accessToken = this._accessToken.asReadonly();
  /** Usuario autenticado, o `null` sin sesión. */
  readonly user = this._user.asReadonly();
  /** Alcance del token: `POS` tras un login por PIN, `FULL` tras un login con clave. */
  readonly scope = this._scope.asReadonly();
  /** `true` si el usuario debe cambiar su clave antes de usar el sistema. */
  readonly mustChangePassword = this._mustChangePassword.asReadonly();
  /** `true` si hay una sesión activa (token en memoria). */
  readonly isAuthenticated = computed(() => this._accessToken() !== null);
  /** Permisos efectivos como conjunto, para consultas O(1). Se recalcula al cambiar el usuario. */
  private readonly permissionSet = computed(() => new Set(this._user()?.permissions ?? []));

  /**
   * Inicia sesión con usuario y clave (`POST /auth/login`).
   *
   * @param request Credenciales.
   * @returns El usuario autenticado. Los errores (`INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`,
   *   `RATE_LIMITED`) llegan como `ApiError`.
   */
  login(request: LoginRequest): Observable<MeResponse> {
    return this.http.post<TokenResponse>(`${AUTH_BASE_PATH}/login`, request).pipe(
      tap((response) => this.applySession(response)),
      map((response) => response.user),
    );
  }

  /**
   * Inicia sesión por PIN en la terminal de la sesión vigente (`POST /auth/pin-login`).
   * El token resultante tiene `scope=POS` (D.4). Requiere terminal activa (`X-Terminal-Id`).
   *
   * @param request Usuario y PIN.
   * @returns El usuario autenticado.
   */
  pinLogin(request: PinLoginRequest): Observable<MeResponse> {
    return this.http.post<TokenResponse>(`${AUTH_BASE_PATH}/pin-login`, request).pipe(
      tap((response) => this.applySession(response)),
      map((response) => response.user),
    );
  }

  /**
   * Cierra la sesión (`POST /auth/logout`) y limpia SIEMPRE el estado local, aunque el servidor
   * falle (p. ej. sin red): el usuario pidió salir y no debe quedar con sesión en pantalla. Un
   * fallo del servidor solo significa que la cookie de refresh podría seguir vigente hasta su
   * vencimiento.
   */
  logout(): Observable<void> {
    return this.http
      .post<void>(`${AUTH_BASE_PATH}/logout`, null, { context: withoutErrorNotification() })
      .pipe(
        catchError(() => of(undefined)),
        map(() => undefined),
        tap(() => this.clearSession()),
      );
  }

  /**
   * Obtiene un access token nuevo con la cookie de refresh (`POST /auth/refresh`).
   *
   * Es single-flight: mientras haya un refresh en curso, todas las llamadas reciben el mismo
   * observable. Si el refresh falla, la sesión local se limpia (el refresh token ya no sirve) y
   * el error se propaga. El header `X-Requested-With` es exigido por el backend como defensa
   * CSRF adicional (API.md F.1).
   *
   * @returns El nuevo access token.
   */
  refresh(): Observable<string> {
    this.refreshInFlight ??= this.http
      .post<TokenResponse>(`${AUTH_BASE_PATH}/refresh`, null, {
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
        // El fallo de refresh lo gestiona quien lo invoca (redirección a login); sin snackbar propio.
        context: withoutErrorNotification(),
      })
      .pipe(
        tap((response) => this.applySession(response)),
        map((response) => response.accessToken),
        catchError((error: unknown) => {
          this.clearSession();
          return throwError(() => error);
        }),
        finalize(() => {
          this.refreshInFlight = null;
        }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    return this.refreshInFlight;
  }

  /**
   * Restaura la sesión al arrancar la app a partir de la cookie de refresh (se registra con
   * `provideAppInitializer`). Nunca falla: sin cookie válida o sin red simplemente no hay
   * sesión y el guard enviará al login. Si el servidor no responde en 8 s se abandona la espera
   * (la petición sigue su curso: si responde después, la sesión se aplica igualmente).
   */
  async restoreSession(): Promise<void> {
    try {
      await firstValueFrom(this.refresh().pipe(timeout({ first: RESTORE_TIMEOUT_MS })));
    } catch {
      // Sin sesión previa (o backend inalcanzable): se continúa sin autenticar.
    }
  }

  /**
   * Recarga el usuario actual (`GET /auth/me`), p. ej. tras cambiar roles o permisos. No altera
   * `mustChangePassword`, que solo informa `TokenResponse`.
   */
  loadMe(): Observable<MeResponse> {
    return this.http.get<MeResponse>(`${AUTH_BASE_PATH}/me`).pipe(
      tap((user) => {
        this._user.set(user);
        this._scope.set(user.scope);
        this.storeContext.reconcile(user.stores, user.defaultStoreId);
      }),
    );
  }

  /** Marca la clave como ya cambiada (la pantalla de cambio de clave lo invoca al terminar). */
  markPasswordChanged(): void {
    this._mustChangePassword.set(false);
  }

  /** Indica si el usuario tiene el permiso `code` (formato `modulo:recurso:accion`). */
  hasPermission(code: string): boolean {
    return this.permissionSet().has(code);
  }

  /** Indica si el usuario tiene AL MENOS UNO de los permisos. Con una lista vacía devuelve `false`. */
  hasAnyPermission(codes: readonly string[]): boolean {
    const granted = this.permissionSet();
    return codes.some((code) => granted.has(code));
  }

  /** Indica si el usuario tiene TODOS los permisos. Con una lista vacía devuelve `false`. */
  hasAllPermissions(codes: readonly string[]): boolean {
    const granted = this.permissionSet();
    return codes.length > 0 && codes.every((code) => granted.has(code));
  }

  /** Aplica la respuesta de un login/refresh al estado en memoria. */
  private applySession(response: TokenResponse): void {
    this._accessToken.set(response.accessToken);
    this._user.set(response.user);
    this._scope.set(response.scope);
    this._mustChangePassword.set(response.mustChangePassword);
    this.storeContext.reconcile(response.user.stores, response.user.defaultStoreId);
  }

  /** Borra token, usuario y estado derivado. La preferencia de local/terminal se conserva. */
  private clearSession(): void {
    this._accessToken.set(null);
    this._user.set(null);
    this._scope.set(null);
    this._mustChangePassword.set(false);
    this.storeContext.clearAvailableStores();
  }
}
