import { HttpClient, HttpContext, HttpErrorResponse, HttpResponse } from '@angular/common/http';
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
import { list16 } from '../../../api/fn/catalog/list-16';
import { list18 } from '../../../api/fn/catalog/list-18';
import { confirm1 } from '../../../api/fn/data-exchange/confirm-1';
import { create17 } from '../../../api/fn/data-exchange/create-17';
import { export$, Export$Params } from '../../../api/fn/data-exchange/export';
import { get7 } from '../../../api/fn/data-exchange/get-7';
import { template } from '../../../api/fn/data-exchange/template';
import { CategoryResponse } from '../../../api/models/category-response';
import { DataExchangeImportJobResponse } from '../../../api/models/data-exchange-import-job-response';
import { PageResponseBrandResponse } from '../../../api/models/page-response-brand-response';
import { StrictHttpResponse } from '../../../api/strict-http-response';
import { ApiError, isApiError } from '../../../core/http/api-error';

/** Tamaño máximo permitido para archivos de importación (20 MB, alineado con backend). */
export const MAX_IMPORT_FILE_BYTES = 20 * 1024 * 1024;

/** Tipos de importación masiva soportados por `/api/v1/catalog/imports`. */
export type ImportType = 'PRODUCTS' | 'PRODUCT_SUPPLIERS' | 'INITIAL_STOCK';

/** Formatos de archivo soportados para plantillas y exportación de catálogo. */
export type ExportFormat = 'xlsx' | 'csv';

/** Resultado binario descargado desde un endpoint de exportación o plantilla. */
export interface DownloadedFile {
  blob: Blob;
  fileName: string;
}

/** Parámetros para crear un trabajo de importación masiva. */
export interface CreateImportParams {
  importType: ImportType;
  dryRun: boolean;
  file: File;
  storeId?: number | null;
}

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
 * Extrae el nombre de archivo desde el encabezado `Content-Disposition` (RFC 6266 / 5987),
 * devolviendo `fallback` si no viene informado.
 */
export function extractFileName(
  contentDisposition: string | null | undefined,
  fallback: string,
): string {
  if (!contentDisposition) {
    return fallback;
  }
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1].trim());
    } catch {
      return utf8Match[1].trim();
    }
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(contentDisposition);
  return plainMatch?.[1]?.trim() || fallback;
}

/** Convierte una respuesta HTTP binaria en {@link DownloadedFile}. */
function toDownloadedFile(
  response: HttpResponse<unknown>,
  fallbackFileName: string,
): DownloadedFile {
  const body = response.body;
  const blob =
    typeof Blob !== 'undefined' && body instanceof Blob
      ? body
      : new Blob([typeof body === 'string' ? body : ''], {
          type: response.headers.get('Content-Type') ?? 'application/octet-stream',
        });
  const fileName = extractFileName(response.headers.get('Content-Disposition'), fallbackFileName);
  return { blob, fileName };
}

/**
 * Servicio de importación y exportación masiva del catálogo (`API.md` F.2, `HU-CAT-10`, `HU-CAT-11`).
 *
 * Delega todas las peticiones en las funciones generadas de `src/app/api/fn/data-exchange` y
 * `src/app/api/fn/catalog`, desenvolviendo cuerpos y errores `Blob` de `ng-openapi-gen`.
 */
@Injectable({ providedIn: 'root' })
export class ImportsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Sube un archivo CSV/XLSX para validación previa (`dryRun=true`) o importación directa
   * (`dryRun=false`) en `POST /api/v1/catalog/imports`.
   */
  createImport(
    params: CreateImportParams,
    context?: HttpContext,
  ): Observable<DataExchangeImportJobResponse> {
    return unwrapApiResponse(
      create17(
        this.http,
        this.apiConfig.rootUrl,
        {
          importType: params.importType,
          dryRun: params.dryRun,
          'X-Store-Id': params.storeId ?? undefined,
          body: { file: params.file },
        },
        context,
      ),
    );
  }

  /** Consulta el estado, resumen y errores de un trabajo de importación (`GET /api/v1/catalog/imports/{id}`). */
  getImport(id: number, context?: HttpContext): Observable<DataExchangeImportJobResponse> {
    return unwrapApiResponse(get7(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Confirma y aplica en una sola transacción una importación previamente validada sin errores
   * (`POST /api/v1/catalog/imports/{id}/confirm`).
   */
  confirmImport(
    id: number,
    storeId?: number | null,
    context?: HttpContext,
  ): Observable<DataExchangeImportJobResponse> {
    return unwrapApiResponse(
      confirm1(
        this.http,
        this.apiConfig.rootUrl,
        { id, 'X-Store-Id': storeId ?? undefined },
        context,
      ),
    );
  }

  /**
   * Descarga la plantilla oficial CSV o XLSX para el tipo de importación indicado
   * (`GET /api/v1/catalog/imports/templates/{importType}`).
   */
  downloadTemplate(
    importType: ImportType,
    format: ExportFormat,
    context?: HttpContext,
  ): Observable<DownloadedFile> {
    const fallback = `plantilla-${importType.toLowerCase()}.${format}`;
    return template(this.http, this.apiConfig.rootUrl, { importType, format }, context).pipe(
      map((response) => toDownloadedFile(response, fallback)),
      unwrapApiError(),
    );
  }

  /**
   * Exporta el catálogo filtrado a CSV o XLSX (`GET /api/v1/products/export`).
   * Si el usuario no tiene `catalog:cost:read`, el backend omite las columnas de costo.
   */
  exportCatalog(params: Export$Params, context?: HttpContext): Observable<DownloadedFile> {
    const format = params.format ?? 'xlsx';
    const fallback = `catalogo-productos.${format}`;
    return export$(this.http, this.apiConfig.rootUrl, params, context).pipe(
      map((response) => toDownloadedFile(response, fallback)),
      unwrapApiError(),
    );
  }

  /** Lista el árbol de categorías para los filtros de exportación. */
  listCategories(context?: HttpContext): Observable<CategoryResponse[]> {
    return unwrapApiResponse(list16(this.http, this.apiConfig.rootUrl, { tree: true }, context));
  }

  /** Lista las marcas activas para los filtros de exportación. */
  listBrands(context?: HttpContext): Observable<PageResponseBrandResponse> {
    return unwrapApiResponse(
      list18(this.http, this.apiConfig.rootUrl, { active: true, page: 0, size: 200 }, context),
    );
  }
}
