import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, catchError, map, switchMap, throwError } from 'rxjs';
import { ApiError, ErrorCodes } from '../../../core/http/api-error';
import {
  OVERRIDE_TOKEN_HEADER,
  OverrideAction,
  OverrideContext,
  OverrideDialogData,
  OverrideResponse,
} from './override.models';
import { OverrideDialog } from './override-dialog';

/**
 * Cabeceras extra que `requestFn` debe agregar a la petición. Vacías en el primer intento;
 * en el reintento contienen `X-Override-Token`.
 */
export type OverrideHeaders = Readonly<Record<string, string>>;

/** Operación a proteger: recibe las cabeceras de override y devuelve la petición HTTP. */
export type OverridableRequest<T> = (overrideHeaders: OverrideHeaders) => Observable<T>;

/**
 * Orquesta el override de supervisor (ADR-19, ROLES_PERMISOS D.4).
 *
 * Uso típico:
 * ```ts
 * // El HttpContext se crea UNA vez, fuera de la función (ver "Idempotencia").
 * const context = withIdempotency();
 * this.override
 *   .withSupervisorOverride((headers) =>
 *     this.http.post('/api/v1/sales', body, { headers, context }),
 *   )
 *   .subscribe(...);
 * ```
 */
@Injectable({ providedIn: 'root' })
export class SupervisorOverrideService {
  private readonly dialog = inject(MatDialog);

  /**
   * Ejecuta una operación y, si el servidor exige autorización, la obtiene y repite la MISMA
   * operación con el token.
   *
   * Contrato:
   * 1. Se invoca `requestFn({})`. Si tiene éxito, su resultado se devuelve sin más.
   * 2. Si falla con 422 `OVERRIDE_REQUIRED` (con `action` y `context` en el error), se abre
   *    {@link OverrideDialog}. Cualquier otro error se propaga sin tocarlo.
   * 3. Si el supervisor cancela el diálogo, se propaga el `OVERRIDE_REQUIRED` ORIGINAL (el
   *    llamador decide qué mostrar; no se pierde el error).
   * 4. Si el supervisor es autorizado, se invoca `requestFn({ 'X-Override-Token': token })`
   *    UNA sola vez; su resultado o error se devuelven tal cual (un segundo `OVERRIDE_REQUIRED`
   *    o `OVERRIDE_INVALID`, p. ej. por cambiar el carrito, se propaga: no hay bucles).
   *
   * Idempotencia: el servidor deduplica por `Idempotency-Key` (ADR-14). La clave vive en el
   * `HttpContext` de la petición (la asigna `idempotencyInterceptor` la primera vez), por lo
   * que `requestFn` DEBE reutilizar el mismo `HttpContext` en ambas invocaciones: créelo fuera
   * de la función. Así el reintento reenvía la misma clave y no duplica la operación. Si
   * `requestFn` creara un contexto nuevo cada vez, el reintento usaría otra clave.
   *
   * Una sola ronda: solo se gestiona UN override por llamada. Si la operación necesita otro
   * distinto (p. ej. descuento y crédito en la misma venta; F.2 de `POST /sales` admite varios
   * tokens separados por coma), el llamador debe orquestar otra ronda, o ampliar este servicio
   * (pendiente para T44). Un segundo `OVERRIDE_REQUIRED` tras el reintento se propaga.
   *
   * Cierre del diálogo: Esc o clic fuera cancelan (igual que Cancelar) salvo mientras se envía.
   *
   * Notas: el primer 422 no genera snackbar (`errorInterceptor` lo omite para este código).
   * El observable resultante emite lo que emita la petición y completa con ella.
   *
   * @param requestFn Construye la petición; debe aplicar `overrideHeaders` a la llamada HTTP.
   */
  withSupervisorOverride<T>(requestFn: OverridableRequest<T>): Observable<T> {
    return requestFn({}).pipe(
      catchError((error: unknown) => {
        const required = toOverrideRequired(error);
        if (required === null) {
          return throwError(() => error);
        }
        return this.askSupervisor(required.data).pipe(
          switchMap((response) =>
            response === undefined
              ? throwError(() => error)
              : requestFn({ [OVERRIDE_TOKEN_HEADER]: response.overrideToken }),
          ),
        );
      }),
    );
  }

  /** Abre el diálogo y emite la respuesta, o `undefined` si se canceló. */
  private askSupervisor(data: OverrideDialogData): Observable<OverrideResponse | undefined> {
    return this.dialog
      .open<OverrideDialog, OverrideDialogData, OverrideResponse | undefined>(OverrideDialog, {
        data,
        width: '460px',
      })
      .afterClosed()
      .pipe(map((result) => result ?? undefined));
  }
}

/**
 * Si el error es un `OVERRIDE_REQUIRED` utilizable, devuelve los datos para el diálogo;
 * si no (otro código, o sin `action`/`context` válidos), `null` y el error sigue su curso.
 */
function toOverrideRequired(error: unknown): { data: OverrideDialogData } | null {
  const apiError = error instanceof HttpErrorResponse ? ApiError.fromHttpError(error) : error;
  if (!(apiError instanceof ApiError) || apiError.code !== ErrorCodes.overrideRequired) {
    return null;
  }
  const { action, context } = apiError.extensions;
  if (typeof action !== 'string' || typeof context !== 'object' || context === null) {
    return null;
  }
  return { data: { action: action as OverrideAction, context: context as OverrideContext } };
}
