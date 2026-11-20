import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../../../api/api-configuration';
import { list15 } from '../../../../api/fn/customers/list-15';
import { CustomerResponse } from '../../../../api/models/customer-response';
import { PageResponseCustomerResponse } from '../../../../api/models/page-response-customer-response';
import { unwrapApiResponse } from '../cart/pos-http.utils';

/** Máximo de clientes por búsqueda del selector (suficiente para elegir con el teclado). */
export const POS_CUSTOMER_SEARCH_LIMIT = 20;

/**
 * Búsqueda de clientes para la venta POS (`HU-POS-16`).
 *
 * Envuelve `GET /api/v1/customers` (endpoint global [G], no requiere `X-Store-Id`) filtrando
 * solo activos, para el selector de cliente del punto de venta. La búsqueda acepta RUT o nombre
 * (parámetro `q` del backend).
 */
@Injectable({ providedIn: 'root' })
export class PosCustomerSearchService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Busca clientes activos por RUT o nombre (`q`).
   *
   * @param q Texto de búsqueda tal como lo escribe el cajero; se recorta en el servicio.
   * @param context Contexto HTTP opcional (p. ej. `withoutErrorNotification()`).
   * @returns Lista de clientes activos ordenada por nombre; vacía si no hay coincidencias.
   */
  searchCustomers(q: string, context?: HttpContext): Observable<CustomerResponse[]> {
    const trimmed = q.trim();
    return unwrapApiResponse(
      list15(
        this.http,
        this.apiConfig.rootUrl,
        {
          q: trimmed || undefined,
          active: true,
          size: POS_CUSTOMER_SEARCH_LIMIT,
          sort: ['name,asc'],
        },
        context,
      ),
    ).pipe(map((page: PageResponseCustomerResponse) => page.content ?? []));
  }
}
