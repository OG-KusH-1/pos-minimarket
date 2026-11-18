import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../../../api/api-configuration';
import { current } from '../../../../api/fn/cash/current';
import { quote } from '../../../../api/fn/sales/quote';
import { CartQuoteRequest } from '../../../../api/models/cart-quote-request';
import { CartQuoteResponse } from '../../../../api/models/cart-quote-response';
import { CashSessionResponse } from '../../../../api/models/cash-session-response';
import { unwrapApiResponse } from './pos-http.utils';

/**
 * Cliente de endpoints POS de cotización de carrito y consulta de turno vigente (`API.md` F.2).
 *
 * Delega las llamadas HTTP en las funciones generadas de `src/app/api`. Los encabezados
 * `X-Store-Id` y `X-Terminal-Id` los inyecta automáticamente `storeContextInterceptor`.
 */
@Injectable({ providedIn: 'root' })
export class PosCartApiService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Cotiza el carrito actual en el servidor (`POST /api/v1/pos/cart/quote`) aplicando
   * promociones, descuentos, prorrateo, impuestos, redondeo legal y overrides requeridos.
   */
  quote(body: CartQuoteRequest, context?: HttpContext): Observable<CartQuoteResponse> {
    return unwrapApiResponse(quote(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /**
   * Consulta si la terminal activa tiene un turno de caja abierto (`GET /api/v1/cash-sessions/current`).
   * Devuelve `null` cuando el servidor responde `204 No Content`.
   */
  getCurrentCashSession(context?: HttpContext): Observable<CashSessionResponse | null> {
    return unwrapApiResponse(current(this.http, this.apiConfig.rootUrl, undefined, context)).pipe(
      map((session) => session ?? null),
    );
  }
}
