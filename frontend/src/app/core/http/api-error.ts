import { HttpErrorResponse } from '@angular/common/http';

/** Códigos de `ErrorCode` (API.md F.1) a los que el frontend reacciona de forma especial. */
export const ErrorCodes = {
  validation: 'VALIDATION_ERROR',
  overrideRequired: 'OVERRIDE_REQUIRED',
  invalidCredentials: 'INVALID_CREDENTIALS',
  unauthenticated: 'UNAUTHENTICATED',
  tokenInvalid: 'TOKEN_INVALID',
  /** Sin respuesta del servidor (red caída, CORS, backend apagado). Solo existe en el cliente. */
  network: 'NETWORK_ERROR',
  /** Respuesta de error que no es un ProblemDetail (p. ej. 502 HTML de un proxy). */
  unknown: 'UNKNOWN_ERROR',
} as const;

/** Error de un campo dentro de `errors[]` (A.5): `{field, code, message}`. */
export interface FieldError {
  readonly field: string;
  readonly code: string;
  readonly message: string;
}

/** Campos estándar del ProblemDetail (RFC 9457 + A.5); el resto se conserva en `extensions`. */
const STANDARD_MEMBERS = new Set([
  'type',
  'title',
  'status',
  'detail',
  'instance',
  'code',
  'traceId',
  'errors',
]);

/**
 * Error de API tipado: representación en el frontend de un ProblemDetail (RFC 9457, A.5).
 *
 * `errorInterceptor` convierte todo `HttpErrorResponse` en una instancia de esta clase, de modo
 * que formularios y servicios solo manejan un tipo (`instanceof ApiError` o {@link isApiError}).
 *
 * - `code`: valor estable de `ErrorCode`; úsese para decidir comportamiento (nunca `message`).
 * - `errors`: detalle por campo de un `VALIDATION_ERROR`, para marcar los controles del formulario.
 * - `extensions`: miembros no estándar del cuerpo. Para `OVERRIDE_REQUIRED` el backend incluye
 *   `action` y `context` (F.1); se asume que viajan como miembros de primer nivel del ProblemDetail
 *   (convención de extensiones de RFC 9457) y se leen aquí. Si T27 fija otra ubicación, solo cambia
 *   {@link ApiError.fromHttpError}.
 */
export class ApiError extends Error {
  override readonly name = 'ApiError';

  constructor(
    /** Estado HTTP; `0` si no hubo respuesta. */
    readonly status: number,
    /** Valor de `ErrorCode`, o `NETWORK_ERROR` / `UNKNOWN_ERROR` si no hay ProblemDetail. */
    readonly code: string,
    message: string,
    /** Identificador de correlación para soporte (también está en los logs del servidor). */
    readonly traceId: string | null,
    readonly errors: readonly FieldError[],
    readonly extensions: Readonly<Record<string, unknown>>,
    cause?: unknown,
  ) {
    super(message, { cause });
  }

  /** `true` si es un `VALIDATION_ERROR` con detalle por campo (lo muestra el formulario). */
  get hasFieldErrors(): boolean {
    return this.code === ErrorCodes.validation && this.errors.length > 0;
  }

  /**
   * Construye un `ApiError` a partir de un error HTTP. Tolera cuerpos que no son ProblemDetail
   * (HTML, texto, `Blob` de descargas, `null`): en ese caso solo se usa el estado HTTP.
   */
  static fromHttpError(response: HttpErrorResponse): ApiError {
    const body = isRecord(response.error) ? response.error : null;
    const code =
      typeof body?.['code'] === 'string'
        ? body['code']
        : response.status === 0
          ? ErrorCodes.network
          : ErrorCodes.unknown;
    const message =
      firstString(body?.['detail'], body?.['title']) ??
      (response.status === 0 ? 'Sin respuesta del servidor' : `Error HTTP ${response.status}`);
    // Respaldo: el header `X-Trace-Id` (p. ej. errores que no llegan con ProblemDetail).
    const traceId =
      (typeof body?.['traceId'] === 'string' ? body['traceId'] : null) ??
      response.headers?.get('X-Trace-Id') ??
      null;
    const extensions = Object.fromEntries(
      Object.entries(body ?? {}).filter(([key]) => !STANDARD_MEMBERS.has(key)),
    );
    return new ApiError(
      response.status,
      code,
      message,
      traceId,
      parseFieldErrors(body?.['errors']),
      extensions,
      response,
    );
  }
}

/** Indica si un valor desconocido es un {@link ApiError}. */
export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function firstString(...values: unknown[]): string | null {
  const found = values.find((value): value is string => typeof value === 'string' && value !== '');
  return found ?? null;
}

/** Filtra `errors[]` conservando solo las entradas bien formadas `{field, code, message}`. */
function parseFieldErrors(value: unknown): FieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((entry: unknown) => {
    if (!isRecord(entry)) {
      return [];
    }
    const { field, code, message } = entry;
    return typeof field === 'string' && typeof code === 'string' && typeof message === 'string'
      ? [{ field, code, message }]
      : [];
  });
}
