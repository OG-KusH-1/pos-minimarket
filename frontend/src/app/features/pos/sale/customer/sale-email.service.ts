import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../../../api/api-configuration';
import { emailTicket } from '../../../../api/fn/sales/email-ticket';
import { unwrapApiResponse } from '../cart/pos-http.utils';
import { withIdempotency } from '../../../../core/http/http-context-tokens';

/**
 * Envío del comprobante de venta por correo electrónico (`HU-POS-11`).
 *
 * Envuelve `POST /api/v1/sales/{id}/email` (`202`): el backend encola el mensaje en la outbox
 * y lo entrega en segundo plano, de modo que la venta nunca falla por un SMTP caído (se
 * reintenta desde la outbox; ver `ADR` de correo en `docs/PLAN.md`).
 */
@Injectable({ providedIn: 'root' })
export class SaleEmailService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Encola el envío del comprobante de la venta al correo indicado.
   *
   * La petición se marca como idempotente (`withIdempotency()`): un reintento accidental no
   * debe duplicar el correo.
   *
   * @param saleId Identificador numérico de la venta.
   * @param email Correo del destinatario (validado por el diálogo llamador).
   * @param context Contexto HTTP opcional (p. ej. `withoutErrorNotification()`).
   * @returns Observable que completa sin valor cuando el backend acepta el encargo (`202`).
   */
  sendTicketByEmail(saleId: number, email: string, context?: HttpContext): Observable<void> {
    const httpContext = withIdempotency(context);
    return unwrapApiResponse(
      emailTicket(this.http, this.apiConfig.rootUrl, { id: saleId, body: { email } }, httpContext),
    ).pipe(map(() => undefined));
  }
}
