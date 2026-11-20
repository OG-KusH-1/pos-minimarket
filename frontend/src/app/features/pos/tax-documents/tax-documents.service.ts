import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../../api/api-configuration';
import { getById3 } from '../../../api/fn/billing/get-by-id-3';
import { getBySaleId } from '../../../api/fn/billing/get-by-sale-id';
import { list19, List19$Params } from '../../../api/fn/billing/list-19';
import { retry } from '../../../api/fn/billing/retry';
import { PageResponseTaxDocumentResponse } from '../../../api/models/page-response-tax-document-response';
import { TaxDocumentResponse } from '../../../api/models/tax-document-response';
import { PageResponse } from '../../../shared/models/page-response';
import { unwrapApiResponse } from '../sale/cart/pos-http.utils';

/**
 * Normaliza la página generada por `ng-openapi-gen` (campos opcionales) al contrato compartido
 * `PageResponse`, de modo que las pantallas no necesiten conversiones de tipos.
 */
function normalizePage(page: PageResponseTaxDocumentResponse): PageResponse<TaxDocumentResponse> {
  return {
    content: page.content ?? [],
    page: page.page ?? 0,
    size: page.size ?? 20,
    totalElements: page.totalElements ?? 0,
    totalPages: page.totalPages ?? 0,
    sort: page.sort ?? [],
  };
}

/**
 * Servicio de consulta y reintento de documentos tributarios (`HU-POS-12`, `API.md` F.2 módulo
 * billing).
 *
 * Envuelve las funciones generadas de `src/app/api/fn/billing`:
 * - `GET /tax-documents` (lista filtrable paginada),
 * - `GET /tax-documents/{id}` (detalle),
 * - `POST /tax-documents/{id}/retry` (reintento de emisión, solo ERROR o REJECTED),
 * - `GET /sales/{id}/tax-document` (documento de una venta; **204** mientras la boleta sigue
 *   pendiente de emisión asíncrona).
 *
 * El 204 NO es un error: `unwrapApiResponse` traduce el cuerpo vacío a `null`, que el llamador
 * muestra como estado «pendiente de emisión» (ver {@link getSaleTaxDocument}).
 */
@Injectable({ providedIn: 'root' })
export class TaxDocumentsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Lista los documentos tributarios del local activo con filtros opcionales.
   *
   * @param params Filtros de `GET /tax-documents`: `status`, `documentType`, `from`, `to`
   *   (fechas `yyyy-MM-dd`), `folio` y paginación `page`/`size`/`sort`.
   * @param context Contexto HTTP opcional (p. ej. `withoutErrorNotification()`).
   */
  listDocuments(
    params: List19$Params,
    context?: HttpContext,
  ): Observable<PageResponse<TaxDocumentResponse>> {
    return unwrapApiResponse(list19(this.http, this.apiConfig.rootUrl, params, context)).pipe(
      map(normalizePage),
    );
  }

  /** Obtiene el detalle de un documento tributario por su identificador. */
  getDocument(id: number, context?: HttpContext): Observable<TaxDocumentResponse> {
    return unwrapApiResponse(getById3(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Reintenta la emisión de un documento en estado ERROR o REJECTED
   * (`POST /tax-documents/{id}/retry`).
   *
   * @returns El documento actualizado tras el reintento (el backend conserva el folio).
   */
  retryDocument(id: number, context?: HttpContext): Observable<TaxDocumentResponse> {
    return unwrapApiResponse(retry(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Obtiene el documento tributario de una venta (`GET /sales/{id}/tax-document`).
   *
   * @returns El documento emitido para la venta, o **`null` cuando el backend responde 204**
   *   (la boleta aún no se emite: la emisión es asíncrona al commit de la venta, `API.md` F.4).
   *   El `null` representa el estado «pendiente de emisión» y NO debe tratarse como error.
   */
  getSaleTaxDocument(
    saleId: number,
    context?: HttpContext,
  ): Observable<TaxDocumentResponse | null> {
    // unwrapApiResponse desarrolla el cuerpo vacío del 204 a `null` (unwrapBody).
    return unwrapApiResponse(
      getBySaleId(this.http, this.apiConfig.rootUrl, { id: saleId }, context),
    );
  }
}
