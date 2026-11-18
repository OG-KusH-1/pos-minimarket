import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiConfiguration } from '../../../../api/api-configuration';
import { resolve } from '../../../../api/fn/sales/resolve';
import { search } from '../../../../api/fn/sales/search';
import { PosProductResult } from '../../../../api/models/pos-product-result';
import { ScanResultResponse } from '../../../../api/models/scan-result-response';
import { unwrapApiResponse } from '../cart/pos-http.utils';

/** Límite máximo de resultados de búsqueda rápida en el POS (`API.md` F.2). */
export const POS_SEARCH_MAX_LIMIT = 20;

/**
 * Servicio POS de búsqueda y resolución de productos (`API.md` F.2, `HU-POS-01`, `HU-POS-02`).
 *
 * Delega en las funciones generadas de `src/app/api/fn/sales`. Los encabezados `X-Store-Id` y
 * `X-Terminal-Id` los inyecta automáticamente `storeContextInterceptor`.
 */
@Injectable({ providedIn: 'root' })
export class PosProductService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Busca productos activos por nombre (trigram sin tildes), SKU, atajo o prefijo de código,
   * incluyendo su stock disponible en el local activo (`GET /api/v1/pos/products/search`).
   */
  searchProducts(
    q: string,
    limit: number = POS_SEARCH_MAX_LIMIT,
    context?: HttpContext,
  ): Observable<PosProductResult[]> {
    const safeLimit = Math.min(Math.max(Math.trunc(limit), 1), POS_SEARCH_MAX_LIMIT);
    return unwrapApiResponse(
      search(this.http, this.apiConfig.rootUrl, { q, limit: safeLimit }, context),
    );
  }

  /**
   * Resuelve un código de barras, código de balanza (`20...`), atajo rápido o SKU
   * (`GET /api/v1/pos/products/resolve`). Puede responder `CODE_NOT_FOUND` o `PRODUCT_INACTIVE`.
   */
  resolveCode(code: string, context?: HttpContext): Observable<ScanResultResponse> {
    return unwrapApiResponse(resolve(this.http, this.apiConfig.rootUrl, { code }, context));
  }
}
