import type { MeResponse, TokenResponse, TokenScope } from './auth.models';

/**
 * Datos de prueba compartidos por los specs de `core`. No se importa desde código de producción
 * (y por eso no entra al bundle); vive junto a los DTO para reutilizar sus tipos.
 */

/** Construye un `MeResponse` válido, sobrescribiendo solo lo indicado. */
export function buildMe(overrides: Partial<MeResponse> = {}): MeResponse {
  return {
    id: 1,
    username: 'cajero1',
    fullName: 'Cajero Uno',
    roles: ['CAJERO'],
    permissions: [],
    stores: [{ id: 10, code: 'L01', name: 'Local Centro', active: true }],
    defaultStoreId: 10,
    limits: {},
    scope: 'FULL',
    hasPin: true,
    ...overrides,
  };
}

/** Construye un `TokenResponse` válido para simular login/refresh. */
export function buildTokenResponse(
  options: {
    accessToken?: string;
    permissions?: readonly string[];
    mustChangePassword?: boolean;
    scope?: TokenScope;
    user?: Partial<MeResponse>;
  } = {},
): TokenResponse {
  const scope = options.scope ?? 'FULL';
  return {
    accessToken: options.accessToken ?? 'token-1',
    tokenType: 'Bearer',
    expiresIn: 900,
    scope,
    mustChangePassword: options.mustChangePassword ?? false,
    user: buildMe({ permissions: options.permissions ?? [], scope, ...options.user }),
  };
}

/** Construye un cuerpo ProblemDetail (RFC 9457, A.5). */
export function buildProblem(
  status: number,
  code: string,
  extra: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    type: `https://pos-minimarket/errors/${code.toLowerCase().replaceAll('_', '-')}`,
    title: code,
    status,
    detail: `detalle de ${code}`,
    instance: '/api/v1/test',
    code,
    traceId: 'trace-123',
    ...extra,
  };
}
