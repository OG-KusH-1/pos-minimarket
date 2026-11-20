import { HttpClient, HttpContext, HttpErrorResponse, HttpRequest } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import {
  Observable,
  OperatorFunction,
  catchError,
  from,
  map,
  of,
  switchMap,
  throwError,
} from 'rxjs';
import { ApiConfiguration } from '../../../api/api-configuration';
import { activate4 } from '../../../api/fn/catalog/activate-4';
import { addBarcode } from '../../../api/fn/catalog/add-barcode';
import { changeCost } from '../../../api/fn/catalog/change-cost';
import { changePrice } from '../../../api/fn/catalog/change-price';
import { create11 } from '../../../api/fn/catalog/create-11';
import { deactivate4 } from '../../../api/fn/catalog/deactivate-4';
import { deleteBarcode } from '../../../api/fn/catalog/delete-barcode';
import { deleteImage } from '../../../api/fn/catalog/delete-image';
import { get3 } from '../../../api/fn/catalog/get-3';
import { list1 } from '../../../api/fn/catalog/list-1';
import { list7 } from '../../../api/fn/catalog/list-7';
import { list11, List11$Params } from '../../../api/fn/catalog/list-11';
import { list16 } from '../../../api/fn/catalog/list-16';
import { list18, List18$Params } from '../../../api/fn/catalog/list-18';
import { priceHistory, PriceHistory$Params } from '../../../api/fn/catalog/price-history';
import { setPack } from '../../../api/fn/catalog/set-pack';
import { setPrimaryBarcode } from '../../../api/fn/catalog/set-primary-barcode';
import { update9 } from '../../../api/fn/catalog/update-9';
import { updateImage } from '../../../api/fn/catalog/update-image';
import { updateTaxes } from '../../../api/fn/catalog/update-taxes';
import { download } from '../../../api/fn/files/download';
import { upload } from '../../../api/fn/files/upload';
import { BarcodeRequest } from '../../../api/models/barcode-request';
import { BarcodeResponse } from '../../../api/models/barcode-response';
import { CategoryResponse } from '../../../api/models/category-response';
import { CostChangeRequest } from '../../../api/models/cost-change-request';
import { FileResponse } from '../../../api/models/file-response';
import { PackRequest } from '../../../api/models/pack-request';
import { PackResponse } from '../../../api/models/pack-response';
import { PageResponseBrandResponse } from '../../../api/models/page-response-brand-response';
import { PageResponsePriceHistoryResponse } from '../../../api/models/page-response-price-history-response';
import { PageResponseProductSummary } from '../../../api/models/page-response-product-summary';
import { PriceChangeRequest } from '../../../api/models/price-change-request';
import { ProductRequest } from '../../../api/models/product-request';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductTaxesRequest } from '../../../api/models/product-taxes-request';
import { ScaleBarcodeConfigResponse } from '../../../api/models/scale-barcode-config-response';
import { TaxResponse } from '../../../api/models/tax-response';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen`.
 *
 * El cliente generado usa `responseType: 'blob'` cuando el OpenAPI declara `* / *`; si el cuerpo
 * llega como `Blob`, se lee y parsea como JSON.
 */
function unwrapBody<T>(body: T): Observable<T> {
  if (typeof Blob !== 'undefined' && body instanceof Blob) {
    return from(body.text()).pipe(
      map((text) => (text ? (JSON.parse(text) as T) : (undefined as T))),
    );
  }
  return of(body);
}

/**
 * Convierte errores `Blob` o texto JSON (`ProblemDetail`) en un {@link ApiError} completo cuando
 * `errorInterceptor` envolvió un `HttpErrorResponse` de `ng-openapi-gen`.
 */
function unwrapApiError<T>(): OperatorFunction<T, T> {
  return catchError((err: unknown) => {
    const httpErr =
      isApiError(err) && err.cause instanceof HttpErrorResponse
        ? err.cause
        : err instanceof HttpErrorResponse
          ? err
          : null;

    if (!httpErr) {
      return throwError(() => err);
    }

    const rebuildFromText = (text: string): Observable<never> => {
      try {
        const parsed: unknown = text ? JSON.parse(text) : null;
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          const cloned = new HttpErrorResponse({
            error: parsed,
            headers: httpErr.headers,
            status: httpErr.status,
            statusText: httpErr.statusText,
            url: httpErr.url ?? undefined,
          });
          return throwError(() => ApiError.fromHttpError(cloned));
        }
      } catch {
        // Cuerpo no JSON: se propaga el error original.
      }
      return throwError(() => err);
    };

    if (typeof Blob !== 'undefined' && httpErr.error instanceof Blob) {
      return from(httpErr.error.text()).pipe(switchMap(rebuildFromText));
    }
    if (typeof httpErr.error === 'string' && httpErr.error.trim().startsWith('{')) {
      return rebuildFromText(httpErr.error);
    }
    return throwError(() => err);
  });
}

/** Desenvuelve respuesta exitosa `Blob` y posibles errores `ProblemDetail` recibidos como `Blob`. */
function unwrapApiResponse<T>(source$: Observable<StrictHttpResponse<T>>): Observable<T> {
  return source$.pipe(
    switchMap((response) => unwrapBody(response.body)),
    unwrapApiError(),
  );
}

/**
 * Envuelve `HttpClient` para fijar `X-Store-Id` en endpoints no globales (como `/api/v1/files`)
 * cuando el contexto activo aún no tiene un local seleccionado.
 */
function httpForStore(http: HttpClient, storeId?: number | null): HttpClient {
  if (storeId === undefined || storeId === null) {
    return http;
  }
  return {
    request: (req: HttpRequest<unknown>) =>
      http.request(req.clone({ setHeaders: { 'X-Store-Id': String(storeId) } })),
  } as unknown as HttpClient;
}

/**
 * Servicio de acceso a la API de productos, códigos de barras, packs, impuestos por producto,
 * precios/costos, historial e imágenes (`API.md` F.2, `HU-CAT-01`..`HU-CAT-13`).
 *
 * Delega todas las peticiones en las funciones generadas de `src/app/api`. Los recursos de
 * catálogo son globales (`[G]`), salvo los endpoints de `/api/v1/files` que admiten `storeId`.
 */
@Injectable({ providedIn: 'root' })
export class ProductsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista productos paginados según filtros (`q`, `categoryId`, `brandId`, `saleType`, etc.). */
  listProducts(
    params?: List11$Params,
    context?: HttpContext,
  ): Observable<PageResponseProductSummary> {
    return unwrapApiResponse(list11(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene la ficha completa de un producto por su identificador. */
  getProduct(id: number, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(get3(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Crea un producto nuevo junto con sus impuestos, códigos iniciales y pack (si aplica). */
  createProduct(body: ProductRequest, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(create11(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza los datos no económicos de un producto (controlando concurrencia por `version`). */
  updateProduct(
    id: number,
    body: ProductRequest,
    context?: HttpContext,
  ): Observable<ProductResponse> {
    return unwrapApiResponse(update9(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Desactiva un producto (queda fuera de venta en POS, conservando su historial). */
  deactivateProduct(id: number, context?: HttpContext): Observable<void> {
    return deactivate4(this.http, this.apiConfig.rootUrl, { id }, context).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Reactiva un producto previamente desactivado. */
  activateProduct(id: number, context?: HttpContext): Observable<void> {
    return activate4(this.http, this.apiConfig.rootUrl, { id }, context).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Cambia el precio de venta bruto (CLP) registrando historial y auditoría (`RN-35`). */
  changePrice(
    id: number,
    body: PriceChangeRequest,
    context?: HttpContext,
  ): Observable<ProductResponse> {
    return unwrapApiResponse(changePrice(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Ajusta manualmente el costo promedio del producto (`COST_MANUAL`). */
  changeCost(
    id: number,
    body: CostChangeRequest,
    context?: HttpContext,
  ): Observable<ProductResponse> {
    return unwrapApiResponse(changeCost(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Lista el historial paginado de precios y costos del producto. */
  listPriceHistory(
    params: PriceHistory$Params,
    context?: HttpContext,
  ): Observable<PageResponsePriceHistoryResponse> {
    return unwrapApiResponse(priceHistory(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Agrega un código de barras a un producto existente. */
  addBarcode(id: number, body: BarcodeRequest, context?: HttpContext): Observable<BarcodeResponse> {
    return unwrapApiResponse(addBarcode(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Elimina un código de barras de un producto. */
  deleteBarcode(id: number, bid: number, context?: HttpContext): Observable<void> {
    return deleteBarcode(this.http, this.apiConfig.rootUrl, { id, bid }, context).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Marca un código de barras como principal del producto. */
  setPrimaryBarcode(id: number, bid: number, context?: HttpContext): Observable<void> {
    return setPrimaryBarcode(this.http, this.apiConfig.rootUrl, { id, bid }, context).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Define o actualiza el producto base y factor de un producto `PACK`. */
  setPack(id: number, body: PackRequest, context?: HttpContext): Observable<PackResponse> {
    return unwrapApiResponse(setPack(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Reemplaza los impuestos del producto y su estado `vatExempt`. */
  updateTaxes(
    id: number,
    body: ProductTaxesRequest,
    context?: HttpContext,
  ): Observable<TaxResponse[]> {
    return unwrapApiResponse(updateTaxes(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Sube un archivo de imagen de producto (`POST /api/v1/files?category=PRODUCT_IMAGE`). */
  uploadProductImage(
    file: File,
    storeId?: number | null,
    context?: HttpContext,
  ): Observable<FileResponse> {
    return unwrapApiResponse(
      upload(
        httpForStore(this.http, storeId),
        this.apiConfig.rootUrl,
        { category: 'PRODUCT_IMAGE', body: { file } },
        context,
      ),
    );
  }

  /** Asocia una imagen ya subida al producto (`PUT /api/v1/products/{id}/image`). */
  updateImage(id: number, fileId: number, context?: HttpContext): Observable<ProductResponse> {
    return unwrapApiResponse(
      updateImage(this.http, this.apiConfig.rootUrl, { id, body: { fileId } }, context),
    );
  }

  /** Desvincula la imagen actual del producto (`DELETE /api/v1/products/{id}/image`). */
  deleteImage(id: number, context?: HttpContext): Observable<void> {
    return deleteImage(this.http, this.apiConfig.rootUrl, { id }, context).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Descarga el binario de la imagen para previsualizarla con cabecera de autorización. */
  downloadProductImage(
    fileId: number,
    storeId?: number | null,
    context?: HttpContext,
  ): Observable<Blob> {
    return download(
      httpForStore(this.http, storeId),
      this.apiConfig.rootUrl,
      { id: fileId },
      context,
    ).pipe(
      map((response) => response.body),
      unwrapApiError(),
    );
  }

  /** Lista el árbol de categorías para selectores y filtros. */
  listCategories(tree = true, context?: HttpContext): Observable<CategoryResponse[]> {
    return unwrapApiResponse(list16(this.http, this.apiConfig.rootUrl, { tree }, context));
  }

  /** Lista marcas para selectores y filtros. */
  listBrands(params?: List18$Params, context?: HttpContext): Observable<PageResponseBrandResponse> {
    return unwrapApiResponse(list18(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Lista los impuestos configurados para asignar al producto. */
  listTaxes(context?: HttpContext): Observable<TaxResponse[]> {
    return unwrapApiResponse(list1(this.http, this.apiConfig.rootUrl, undefined, context));
  }

  /** Lista configuraciones de balanza para informar compatibilidad de `SCALE_PLU`. */
  listScaleConfigs(context?: HttpContext): Observable<ScaleBarcodeConfigResponse[]> {
    return unwrapApiResponse(list7(this.http, this.apiConfig.rootUrl, undefined, context));
  }
}
