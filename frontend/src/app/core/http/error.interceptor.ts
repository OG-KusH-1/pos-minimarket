import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { ApiError, ErrorCodes } from './api-error';
import { SKIP_ERROR_NOTIFICATION } from './http-context-tokens';

/** Duración del snackbar de error, en milisegundos. */
const SNACKBAR_DURATION_MS = 7000;

/** Mensajes en español por `ErrorCode` para los errores transversales (F.1). */
const MESSAGES_BY_CODE: Readonly<Record<string, string>> = {
  [ErrorCodes.unauthenticated]: 'Tu sesión expiró. Inicia sesión nuevamente.',
  [ErrorCodes.tokenInvalid]: 'Tu sesión expiró. Inicia sesión nuevamente.',
  [ErrorCodes.invalidCredentials]: 'Usuario o contraseña incorrectos.',
  ACCESS_DENIED: 'No tienes permiso para realizar esta acción.',
  STORE_ACCESS_DENIED: 'No tienes acceso al local seleccionado.',
  ACCOUNT_LOCKED: 'La cuenta está bloqueada temporalmente.',
  PIN_LOCKED: 'El PIN está bloqueado temporalmente.',
  RATE_LIMITED: 'Demasiados intentos. Espera un momento e inténtalo nuevamente.',
  CONCURRENT_MODIFICATION:
    'El registro fue modificado por otra persona. Recarga la información e inténtalo de nuevo.',
  [ErrorCodes.network]: 'No se pudo conectar con el servidor. Revisa tu conexión.',
};

/**
 * Convierte los errores HTTP en {@link ApiError} y avisa al usuario con un snackbar en español.
 *
 * Reglas del snackbar:
 * - NO se muestra para `VALIDATION_ERROR` con `errors[]` (los formularios marcan sus campos) ni
 *   para `OVERRIDE_REQUIRED` (lo atiende el diálogo de supervisor, ADR-19).
 * - Tampoco si la petición se marcó con `SKIP_ERROR_NOTIFICATION` (el llamador lo muestra).
 * - Para los 5xx el mensaje es genérico (nunca se muestra el detalle interno) e incluye el
 *   `traceId` para que el usuario pueda reportarlo a soporte.
 * - Para el resto se prefiere el `detail` del ProblemDetail (mensaje de negocio del backend) y,
 *   si no existe, un texto genérico según el estado HTTP.
 *
 * Debe registrarse como el interceptor MÁS EXTERNO: así ve el resultado final, después de que
 * `authInterceptor` haya refrescado el token y reintentado, y no avisa de 401 ya recuperados.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const snackBar = inject(MatSnackBar);
  const notify = !req.context.get(SKIP_ERROR_NOTIFICATION);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) {
        return throwError(() => error);
      }
      const apiError = ApiError.fromHttpError(error);
      if (notify && shouldNotify(apiError)) {
        snackBar.open(buildMessage(apiError), 'Cerrar', { duration: SNACKBAR_DURATION_MS });
      }
      return throwError(() => apiError);
    }),
  );
};

/** Errores que se atienden en otra parte de la UI y no deben generar un snackbar. */
function shouldNotify(error: ApiError): boolean {
  return !error.hasFieldErrors && error.code !== ErrorCodes.overrideRequired;
}

/** Mensaje en español para el usuario. Ver las reglas en la documentación del interceptor. */
function buildMessage(error: ApiError): string {
  if (error.status >= 500) {
    const base = 'Ocurrió un error inesperado en el servidor.';
    return error.traceId === null ? base : `${base} Código de seguimiento: ${error.traceId}`;
  }
  const byCode = MESSAGES_BY_CODE[error.code];
  if (byCode !== undefined) {
    return byCode;
  }
  if (error.code !== ErrorCodes.unknown && error.message !== '') {
    return error.message;
  }
  return fallbackByStatus(error.status);
}

function fallbackByStatus(status: number): string {
  switch (status) {
    case 400:
      return 'Los datos enviados no son válidos.';
    case 401:
      return 'Tu sesión expiró. Inicia sesión nuevamente.';
    case 403:
      return 'No tienes permiso para realizar esta acción.';
    case 404:
      return 'No se encontró el recurso solicitado.';
    case 409:
      return 'La operación entra en conflicto con el estado actual de los datos.';
    default:
      return 'No se pudo completar la operación.';
  }
}
