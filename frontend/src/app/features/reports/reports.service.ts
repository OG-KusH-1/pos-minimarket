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
import { ApiConfiguration } from '../../api/api-configuration';
import { list16 } from '../../api/fn/catalog/list-16';
import { abc, Abc$Params } from '../../api/fn/reporting/abc';
import { exportAbc } from '../../api/fn/reporting/export-abc';
import { exportCashDifferences } from '../../api/fn/reporting/export-cash-differences';
import { exportInventoryValuation } from '../../api/fn/reporting/export-inventory-valuation';
import { exportMargin } from '../../api/fn/reporting/export-margin';
import { exportNoRotation } from '../../api/fn/reporting/export-no-rotation';
import { exportProductRanking } from '../../api/fn/reporting/export-product-ranking';
import { exportPurchasesBySupplier } from '../../api/fn/reporting/export-purchases-by-supplier';
import { exportSales } from '../../api/fn/reporting/export-sales';
import { exportSalesByCashier } from '../../api/fn/reporting/export-sales-by-cashier';
import { exportSalesByPaymentMethod } from '../../api/fn/reporting/export-sales-by-payment-method';
import { exportShrinkage } from '../../api/fn/reporting/export-shrinkage';
import { exportShrinkageTopProducts } from '../../api/fn/reporting/export-shrinkage-top-products';
import {
  getCashDifferencesReport,
  GetCashDifferencesReport$Params,
} from '../../api/fn/reporting/get-cash-differences-report';
import {
  getInventoryValuationReport,
  GetInventoryValuationReport$Params,
} from '../../api/fn/reporting/get-inventory-valuation-report';
import { getMarginReport, GetMarginReport$Params } from '../../api/fn/reporting/get-margin-report';
import {
  getPurchasesBySupplierReport,
  GetPurchasesBySupplierReport$Params,
} from '../../api/fn/reporting/get-purchases-by-supplier-report';
import {
  getShrinkageReport,
  GetShrinkageReport$Params,
} from '../../api/fn/reporting/get-shrinkage-report';
import { getTopShrinkageProductsReport } from '../../api/fn/reporting/get-top-shrinkage-products-report';
import { noRotation, NoRotation$Params } from '../../api/fn/reporting/no-rotation';
import { productRanking, ProductRanking$Params } from '../../api/fn/reporting/product-ranking';
import { sales, Sales$Params } from '../../api/fn/reporting/sales';
import { salesByCashier, SalesByCashier$Params } from '../../api/fn/reporting/sales-by-cashier';
import {
  salesByPaymentMethod,
  SalesByPaymentMethod$Params,
} from '../../api/fn/reporting/sales-by-payment-method';
import { listUsers } from '../../api/fn/security/list-users';
import { CategoryResponse } from '../../api/models/category-response';
import { ReportResponseAbcRow } from '../../api/models/report-response-abc-row';
import { ReportResponseCashDifferenceRow } from '../../api/models/report-response-cash-difference-row';
import { ReportResponseCashierSalesRow } from '../../api/models/report-response-cashier-sales-row';
import { ReportResponseInventoryValuationRow } from '../../api/models/report-response-inventory-valuation-row';
import { ReportResponseMarginRow } from '../../api/models/report-response-margin-row';
import { ReportResponseNoRotationRow } from '../../api/models/report-response-no-rotation-row';
import { ReportResponsePaymentMethodRow } from '../../api/models/report-response-payment-method-row';
import { ReportResponseProductRankingRow } from '../../api/models/report-response-product-ranking-row';
import { ReportResponsePurchasesBySupplierRow } from '../../api/models/report-response-purchases-by-supplier-row';
import { ReportResponseSalesReportRow } from '../../api/models/report-response-sales-report-row';
import { ReportResponseShrinkageReportRow } from '../../api/models/report-response-shrinkage-report-row';
import { UserSummary } from '../../api/models/user-summary';
import { StrictHttpResponse } from '../../api/strict-http-response';
import { ApiError, isApiError } from '../../core/http/api-error';
import {
  AbcMetric,
  ExportFormat,
  InventoryValuationGroupBy,
  MarginGroupBy,
  REPORT_DEFINITIONS,
  RankingMetric,
  RankingOrder,
  ReportKey,
  SalesGroupBy,
  ShrinkageGroupBy,
} from './reports.models';

/** Archivo binario descargado desde un endpoint `/export` de reportes. */
export interface DownloadedFile {
  readonly blob: Blob;
  readonly fileName: string;
}

/** Parámetros para consultar el ranking de top productos con mayor merma. */
export interface TopShrinkageProductsQuery {
  readonly from: string;
  readonly to: string;
  readonly storeIds?: number[];
  readonly limit?: number;
}

/** Parámetros unificados para exportar cualquiera de los 12 reportes a XLSX o PDF. */
export interface ReportExportParams {
  readonly format: ExportFormat;
  readonly from?: string;
  readonly to?: string;
  readonly storeIds?: number[];
  readonly salesGroupBy?: SalesGroupBy;
  readonly rankingOrder?: RankingOrder;
  readonly rankingMetric?: RankingMetric;
  readonly limit?: number;
  readonly categoryId?: number;
  readonly days?: number;
  readonly abcMetric?: AbcMetric;
  readonly marginGroupBy?: MarginGroupBy;
  readonly valuationGroupBy?: InventoryValuationGroupBy;
  readonly shrinkageGroupBy?: ShrinkageGroupBy;
  readonly cashierId?: number;
  readonly withDifference?: boolean;
}

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen`.
 *
 * Los endpoints de `reporting` declaran `Accept: * / *` y `responseType: 'blob'`; cuando la
 * respuesta es JSON, se lee el texto del `Blob` de forma asíncrona y se parsea.
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
 * `errorInterceptor` envolvió un `HttpErrorResponse` con `responseType: 'blob'`.
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
          const obj = parsed as Record<string, unknown>;
          const normalized =
            typeof obj['detail'] === 'string' || typeof obj['title'] === 'string'
              ? obj
              : typeof obj['message'] === 'string'
                ? { ...obj, detail: obj['message'] }
                : obj;
          const cloned = new HttpErrorResponse({
            error: normalized,
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

/** Desenvuelve respuesta JSON recibida como `Blob` y posibles errores `ProblemDetail` en `Blob`. */
function unwrapApiResponse<T>(source$: Observable<StrictHttpResponse<T>>): Observable<T> {
  return source$.pipe(
    switchMap((response) => unwrapBody(response.body)),
    unwrapApiError(),
  );
}

/**
 * Extrae el nombre de archivo desde `Content-Disposition` (RFC 6266 / 5987), usando `fallback` si
 * el header no viene informado.
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

/** Dispara la descarga en el navegador para un {@link DownloadedFile}. */
export function triggerBrowserDownload(file: DownloadedFile): void {
  if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
    return;
  }
  const objectUrl = URL.createObjectURL(file.blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = file.fileName;
  link.click();
  URL.revokeObjectURL(objectUrl);
}

/**
 * Servicio de acceso a los endpoints de reportes y exportación (`API.md` F.2/F.3,
 * `HU-REP-02..10`, `HU-MER-04`, `HU-MER-05`).
 *
 * Delega todas las peticiones en las funciones generadas de `src/app/api/fn/reporting`. Los
 * endpoints JSON reciben respuestas `Blob` de `ng-openapi-gen` que se parsean con `blob.text()`,
 * mientras que los `/export` devuelven el `Blob` binario y el nombre de archivo de
 * `Content-Disposition`.
 */
@Injectable({ providedIn: 'root' })
export class ReportsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Reporte de ventas netas agrupadas por día, hora, día de semana, categoría o local. */
  getSalesReport(
    params: Sales$Params,
    context?: HttpContext,
  ): Observable<ReportResponseSalesReportRow> {
    return unwrapApiResponse(sales(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Reporte de desempeño, anulaciones, devoluciones y overrides por cajero. */
  getSalesByCashier(
    params: SalesByCashier$Params,
    context?: HttpContext,
  ): Observable<ReportResponseCashierSalesRow> {
    return unwrapApiResponse(salesByCashier(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Reporte de recaudación neta de reembolsos por medio de pago. */
  getSalesByPaymentMethod(
    params: SalesByPaymentMethod$Params,
    context?: HttpContext,
  ): Observable<ReportResponsePaymentMethodRow> {
    return unwrapApiResponse(
      salesByPaymentMethod(this.http, this.apiConfig.rootUrl, params, context),
    );
  }

  /** Ranking de productos más o menos vendidos en cantidad o venta neta. */
  getProductRanking(
    params: ProductRanking$Params,
    context?: HttpContext,
  ): Observable<ReportResponseProductRankingRow> {
    return unwrapApiResponse(productRanking(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Productos con stock positivo y sin ventas en la ventana de días indicada. */
  getNoRotation(
    params: NoRotation$Params,
    context?: HttpContext,
  ): Observable<ReportResponseNoRotationRow> {
    return unwrapApiResponse(noRotation(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Clasificación Pareto ABC de productos por monto, cantidad o margen. */
  getAbc(params: Abc$Params, context?: HttpContext): Observable<ReportResponseAbcRow> {
    return unwrapApiResponse(abc(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Reporte de margen bruto y utilidad sobre ventas netas. */
  getMarginReport(
    params: GetMarginReport$Params,
    context?: HttpContext,
  ): Observable<ReportResponseMarginRow> {
    return unwrapApiResponse(getMarginReport(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Valorización actual del inventario a costo promedio ponderado. */
  getInventoryValuation(
    params?: GetInventoryValuationReport$Params,
    context?: HttpContext,
  ): Observable<ReportResponseInventoryValuationRow> {
    return unwrapApiResponse(
      getInventoryValuationReport(this.http, this.apiConfig.rootUrl, params, context),
    );
  }

  /** Reporte de compras agregadas por proveedor con lead time y tasa de cumplimiento. */
  getPurchasesBySupplier(
    params: GetPurchasesBySupplierReport$Params,
    context?: HttpContext,
  ): Observable<ReportResponsePurchasesBySupplierRow> {
    return unwrapApiResponse(
      getPurchasesBySupplierReport(this.http, this.apiConfig.rootUrl, params, context),
    );
  }

  /** Reporte de mermas aprobadas en unidades, costo y porcentaje sobre ventas netas. */
  getShrinkageReport(
    params: GetShrinkageReport$Params,
    context?: HttpContext,
  ): Observable<ReportResponseShrinkageReportRow> {
    return unwrapApiResponse(
      getShrinkageReport(this.http, this.apiConfig.rootUrl, params, context),
    );
  }

  /** Ranking de productos con mayor costo acumulado de mermas aprobadas. */
  getTopShrinkageProducts(
    params: TopShrinkageProductsQuery,
    context?: HttpContext,
  ): Observable<ReportResponseShrinkageReportRow> {
    return unwrapApiResponse(
      getTopShrinkageProductsReport(
        this.http,
        this.apiConfig.rootUrl,
        {
          from: params.from,
          to: params.to,
          storeIds: params.storeIds,
          limit: params.limit !== undefined ? String(params.limit) : undefined,
        },
        context,
      ),
    );
  }

  /** Reporte de diferencias de arqueo en turnos de caja cerrados. */
  getCashDifferences(
    params: GetCashDifferencesReport$Params,
    context?: HttpContext,
  ): Observable<ReportResponseCashDifferenceRow> {
    return unwrapApiResponse(
      getCashDifferencesReport(this.http, this.apiConfig.rootUrl, params, context),
    );
  }

  /**
   * Exporta cualquiera de los 12 reportes a Excel (`.xlsx`) o PDF (`.pdf`) respetando los filtros
   * activos (`HU-REP-10`). Requiere `report:export:execute` además del permiso del reporte.
   */
  exportReport(
    reportKey: ReportKey,
    params: ReportExportParams,
    context?: HttpContext,
  ): Observable<DownloadedFile> {
    const def = REPORT_DEFINITIONS.find((item) => item.key === reportKey);
    const fallback = `${def?.fallbackFileBase ?? 'reporte'}.${params.format}`;
    const response$ = this.dispatchExportRequest(reportKey, params, context);
    return response$.pipe(
      map((response) => toDownloadedFile(response, fallback)),
      unwrapApiError(),
    );
  }

  /** Lista el árbol de categorías para el filtro opcional del ranking de productos (`[G]`). */
  listCategories(context?: HttpContext): Observable<CategoryResponse[]> {
    return unwrapApiResponse(list16(this.http, this.apiConfig.rootUrl, { tree: true }, context));
  }

  /** Lista usuarios activos para el filtro opcional de cajero en diferencias de caja (`[G]`). */
  listUsers(context?: HttpContext): Observable<UserSummary[]> {
    return unwrapApiResponse(
      listUsers(
        this.http,
        this.apiConfig.rootUrl,
        { active: true, page: 0, size: 200, sort: ['fullName,asc'] },
        context,
      ),
    ).pipe(map((page) => page.content ?? []));
  }

  private dispatchExportRequest(
    reportKey: ReportKey,
    params: ReportExportParams,
    context?: HttpContext,
  ): Observable<StrictHttpResponse<string>> {
    const base = {
      from: params.from,
      to: params.to,
      storeIds: params.storeIds,
      format: params.format,
    };

    switch (reportKey) {
      case 'sales':
        return exportSales(
          this.http,
          this.apiConfig.rootUrl,
          { ...base, groupBy: params.salesGroupBy },
          context,
        );
      case 'sales-by-cashier':
        return exportSalesByCashier(this.http, this.apiConfig.rootUrl, base, context);
      case 'sales-by-payment-method':
        return exportSalesByPaymentMethod(this.http, this.apiConfig.rootUrl, base, context);
      case 'product-ranking':
        return exportProductRanking(
          this.http,
          this.apiConfig.rootUrl,
          {
            ...base,
            order: params.rankingOrder,
            metric: params.rankingMetric,
            limit: params.limit,
            categoryId: params.categoryId,
          },
          context,
        );
      case 'no-rotation':
        return exportNoRotation(
          this.http,
          this.apiConfig.rootUrl,
          { ...base, days: params.days },
          context,
        );
      case 'abc':
        return exportAbc(
          this.http,
          this.apiConfig.rootUrl,
          { ...base, metric: params.abcMetric },
          context,
        );
      case 'margin':
        return exportMargin(
          this.http,
          this.apiConfig.rootUrl,
          { ...base, groupBy: params.marginGroupBy },
          context,
        );
      case 'inventory-valuation':
        return exportInventoryValuation(
          this.http,
          this.apiConfig.rootUrl,
          { ...base, groupBy: params.valuationGroupBy },
          context,
        );
      case 'purchases-by-supplier':
        return exportPurchasesBySupplier(this.http, this.apiConfig.rootUrl, base, context);
      case 'shrinkage':
        return exportShrinkage(
          this.http,
          this.apiConfig.rootUrl,
          { ...base, groupBy: params.shrinkageGroupBy },
          context,
        );
      case 'shrinkage-top-products':
        return exportShrinkageTopProducts(
          this.http,
          this.apiConfig.rootUrl,
          { ...base, limit: params.limit },
          context,
        );
      case 'cash-differences':
        return exportCashDifferences(
          this.http,
          this.apiConfig.rootUrl,
          {
            ...base,
            cashierId: params.cashierId,
            withDifference: params.withDifference,
          },
          context,
        );
    }
  }
}
