/**
 * DTO del grupo Auth (API.md F.3) derivados de los modelos generados en `src/app/api/models`.
 *
 * Los esquemas OpenAPI de respuesta marcan los campos como opcionales (Java DTO sin `@NotNull`
 * en respuestas); aquí se estrechan con `Required<...>` para conservar la seguridad de tipos
 * del núcleo sin romper a los consumidores.
 */
import type { LoginRequest } from '../../api/models/login-request';
import type { MeResponse as GeneratedMeResponse } from '../../api/models/me-response';
import type { PinLoginRequest } from '../../api/models/pin-login-request';
import type { SessionTerminalRequest } from '../../api/models/session-terminal-request';
import type { StoreSummary as GeneratedStoreSummary } from '../../api/models/store-summary';
import type { TokenResponse as GeneratedTokenResponse } from '../../api/models/token-response';

export type { LoginRequest, PinLoginRequest, SessionTerminalRequest };

/** Alcance del token: `FULL` (login con clave) o `POS` (login por PIN, solo ventas/caja/clientes). */
export type TokenScope = NonNullable<GeneratedTokenResponse['scope']>;

/** Resumen de un local (F.3, Organization). */
export type StoreSummary = Readonly<Required<GeneratedStoreSummary>>;

/**
 * Usuario autenticado (`GET /auth/me`).
 *
 * `limits`: `null` = sin tope; una clave ausente equivale a 0 (D.3).
 */
export interface MeResponse extends Omit<
  Required<GeneratedMeResponse>,
  'defaultStoreId' | 'limits' | 'stores' | 'roles' | 'permissions'
> {
  readonly roles: readonly string[];
  /** Códigos efectivos con formato `modulo:recurso:accion` (catálogo D.1). */
  readonly permissions: readonly string[];
  readonly stores: readonly StoreSummary[];
  readonly defaultStoreId: number | null;
  readonly limits: Readonly<Record<string, number | null>>;
}

/** Respuesta de `POST /auth/login`, `/auth/refresh` y `/auth/pin-login`. */
export interface TokenResponse extends Omit<Required<GeneratedTokenResponse>, 'user'> {
  readonly user: MeResponse;
}
