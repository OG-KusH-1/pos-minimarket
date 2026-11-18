import { HttpErrorResponse } from '@angular/common/http';
import { ApiError, ErrorCodes } from '../../core/http/api-error';

/**
 * Extrae un {@link ApiError} a partir de un error cualquiera.
 *
 * Con `errorInterceptor` activo, las respuestas de error en endpoints generados con
 * `responseType: 'text'` o `'blob'` llegan envueltas en un `ApiError` con `code: 'UNKNOWN_ERROR'`
 * y el `HttpErrorResponse` original en `error.cause`. En ese caso se intenta leer y parsear el
 * cuerpo `string` o `Blob` como `ProblemDetail` JSON para recuperar el `ErrorCode` y `errors[]`.
 */
export async function extractApiError(error: unknown): Promise<ApiError | null> {
  if (error instanceof ApiError) {
    if (error.code === ErrorCodes.unknown && error.cause instanceof HttpErrorResponse) {
      const unwrapped = await parseHttpErrorBody(error.cause);
      if (unwrapped !== null) {
        return unwrapped;
      }
    }
    return error;
  }
  if (error instanceof HttpErrorResponse) {
    return (await parseHttpErrorBody(error)) ?? ApiError.fromHttpError(error);
  }
  return null;
}

/**
 * Traduce un error de autenticación a un mensaje en español legible para el usuario.
 *
 * @param error Error capturado en la suscripción o `await` (o un `ApiError` ya extraído).
 * @param messagesByCode Mapa de `ErrorCode` a mensaje específico de la pantalla.
 * @param fallback Mensaje por defecto si el código no está mapeado ni trae `detail`.
 */
export async function resolveAuthErrorMessage(
  error: unknown,
  messagesByCode: Readonly<Record<string, string>>,
  fallback: string,
): Promise<string> {
  const apiError = await extractApiError(error);
  if (apiError === null) {
    return fallback;
  }
  if (apiError.status >= 500) {
    const base = 'Ocurrió un error inesperado en el servidor.';
    return apiError.traceId === null ? base : `${base} Código de seguimiento: ${apiError.traceId}`;
  }
  const mapped = messagesByCode[apiError.code];
  if (mapped !== undefined) {
    return mapped;
  }
  if (apiError.message && !apiError.message.startsWith('Error HTTP ')) {
    return apiError.message;
  }
  return fallback;
}

async function parseHttpErrorBody(response: HttpErrorResponse): Promise<ApiError | null> {
  let rawText: string | null = null;
  if (typeof response.error === 'string') {
    rawText = response.error;
  } else if (typeof Blob !== 'undefined' && response.error instanceof Blob) {
    rawText = await response.error.text();
  }
  if (rawText === null || rawText.trim() === '') {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(rawText);
    if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
      return ApiError.fromHttpError(
        new HttpErrorResponse({
          error: parsed,
          headers: response.headers,
          status: response.status,
          statusText: response.statusText,
          url: response.url ?? undefined,
        }),
      );
    }
  } catch {
    // Cuerpo no JSON: se conserva el error original.
  }
  return null;
}

/**
 * Valida y sanea el parámetro `returnUrl` recibido por query string (L-06).
 *
 * Previene redirecciones abiertas aceptando únicamente rutas internas que comiencen con `/` y
 * no con `//` ni `/\` (y evitando volver en bucle a `/login`).
 */
export function sanitizeReturnUrl(raw: string | null | undefined): string {
  if (typeof raw !== 'string') {
    return '/';
  }
  const trimmed = raw.trim();
  if (!trimmed.startsWith('/') || trimmed.startsWith('//') || trimmed.startsWith('/\\')) {
    return '/';
  }
  if (trimmed === '/login' || trimmed.startsWith('/login?') || trimmed.startsWith('/login/')) {
    return '/';
  }
  return trimmed;
}
