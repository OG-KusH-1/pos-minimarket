import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, throwError } from 'rxjs';
import { RequiredOverride } from '../../../../api/models/required-override';
import { ApiError, ErrorCodes, isApiError } from '../../../../core/http/api-error';
import {
  OVERRIDE_TOKEN_HEADER,
  OverrideHeaders,
  SupervisorOverrideService,
} from '../../../../shared/ui/override-dialog';

/**
 * Orquesta autorizaciones de supervisor para el POS sobre {@link SupervisorOverrideService}.
 *
 * Resuelve tanto solicitudes individuales anticipadas a partir de `requiredOverrides` de
 * `/pos/cart/quote` como múltiples rondas secuenciales (L-05) cuando una operación (p. ej.
 * `POST /sales`) requiere más de un override distinto (`X-Override-Token` separado por comas).
 */
@Injectable({ providedIn: 'root' })
export class PosOverrideService {
  private readonly overrideService = inject(SupervisorOverrideService);

  /**
   * Abre el diálogo de supervisor mediante `SupervisorOverrideService.withSupervisorOverride`
   * para un `RequiredOverride` informado por `/pos/cart/quote`.
   *
   * @returns El token otorgado, o `null` si el usuario canceló el diálogo.
   */
  authorizeRequiredOverride(required: RequiredOverride): Observable<string | null> {
    if (!required.action || !required.context) {
      return of(null);
    }
    const syntheticError = new ApiError(
      422,
      ErrorCodes.overrideRequired,
      'Se requiere autorización de supervisor.',
      null,
      [],
      {
        action: required.action,
        context: required.context,
      },
    );

    return this.overrideService
      .withSupervisorOverride<string>((headers: OverrideHeaders) => {
        const token = headers[OVERRIDE_TOKEN_HEADER];
        if (!token) {
          return throwError(() => syntheticError);
        }
        return of(token);
      })
      .pipe(
        map((token) => token),
        catchError((error: unknown) => {
          if (error === syntheticError) {
            return of(null);
          }
          return throwError(() => error);
        }),
      );
  }

  /**
   * Ejecuta una operación que puede requerir uno o más overrides secuenciales (L-05), acumulando
   * los tokens obtenidos en `X-Override-Token` separados por coma (API.md F.2).
   *
   * Si el usuario cancela cualquiera de las rondas, se propaga el `OVERRIDE_REQUIRED` de esa
   * ronda sin volver a abrir el diálogo.
   */
  withMultipleSupervisorOverrides<T>(
    requestFn: (overrideTokenHeader: string | undefined) => Observable<T>,
    initialTokens: readonly string[] = [],
    maxRounds = 5,
  ): Observable<T> {
    let accumulatedTokens = initialTokens.filter((token) => token.trim() !== '');

    const runRound = (remainingRounds: number): Observable<T> => {
      let obtainedNewTokenInRound = false;
      return this.overrideService
        .withSupervisorOverride((headers: OverrideHeaders) => {
          const newToken = headers[OVERRIDE_TOKEN_HEADER];
          if (newToken) {
            obtainedNewTokenInRound = true;
            accumulatedTokens = [...accumulatedTokens, newToken];
          }
          const headerValue =
            accumulatedTokens.length > 0 ? accumulatedTokens.join(',') : undefined;
          return requestFn(headerValue);
        })
        .pipe(
          catchError((error: unknown) => {
            if (remainingRounds > 1 && obtainedNewTokenInRound && isOverrideRequiredError(error)) {
              return runRound(remainingRounds - 1);
            }
            return throwError(() => error);
          }),
        );
    };

    return runRound(Math.max(1, maxRounds));
  }
}

function isOverrideRequiredError(error: unknown): boolean {
  return (
    isApiError(error) &&
    error.code === ErrorCodes.overrideRequired &&
    typeof error.extensions['action'] === 'string' &&
    typeof error.extensions['context'] === 'object' &&
    error.extensions['context'] !== null
  );
}
