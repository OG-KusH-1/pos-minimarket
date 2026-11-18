import { HttpErrorResponse } from '@angular/common/http';
import { ApiError, ErrorCodes } from '../../core/http/api-error';
import { extractApiError, resolveAuthErrorMessage } from './auth-errors';

describe('auth-errors', () => {
  it('desenvuelve ProblemDetail en string JSON desde error.cause cuando errorInterceptor devuelve UNKNOWN_ERROR', async () => {
    const httpError = new HttpErrorResponse({
      status: 422,
      statusText: 'Unprocessable Entity',
      error: JSON.stringify({
        code: 'PASSWORD_POLICY',
        detail: 'La clave no cumple la política.',
      }),
    });
    const wrapped = ApiError.fromHttpError(httpError);
    expect(wrapped.code).toBe(ErrorCodes.unknown);

    const extracted = await extractApiError(wrapped);
    expect(extracted?.code).toBe('PASSWORD_POLICY');

    const message = await resolveAuthErrorMessage(
      wrapped,
      { PASSWORD_POLICY: 'Política rechazada.' },
      'Fallback',
    );
    expect(message).toBe('Política rechazada.');
  });

  it('desenvuelve ProblemDetail en Blob JSON desde error.cause cuando errorInterceptor devuelve UNKNOWN_ERROR', async () => {
    const blob = new Blob(
      [
        JSON.stringify({
          code: 'TERMINAL_INVALID',
          detail: 'Terminal inactiva.',
        }),
      ],
      { type: 'application/problem+json' },
    );
    const httpError = new HttpErrorResponse({
      status: 422,
      statusText: 'Unprocessable Entity',
      error: blob,
    });
    const wrapped = ApiError.fromHttpError(httpError);
    expect(wrapped.code).toBe(ErrorCodes.unknown);

    const extracted = await extractApiError(wrapped);
    expect(extracted?.code).toBe('TERMINAL_INVALID');

    const message = await resolveAuthErrorMessage(
      wrapped,
      { TERMINAL_INVALID: 'La terminal seleccionada no está disponible.' },
      'Fallback',
    );
    expect(message).toBe('La terminal seleccionada no está disponible.');
  });
});
