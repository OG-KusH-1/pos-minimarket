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
import { dashboard, Dashboard$Params } from '../../api/fn/reporting/dashboard';
import { exportDashboard, ExportDashboard$Params } from '../../api/fn/reporting/export-dashboard';
import { DashboardResponse } from '../../api/models/dashboard-response';
import { StrictHttpResponse } from '../../api/strict-http-response';
import { ApiError, isApiError } from '../../core/http/api-error';
import {
  DashboardExportFormat,
  DashboardFilter,
  DownloadedDashboardFile,
  extractFileName,
} from './dashboard.models';

/** Parámetros para exportar el dashboard diario en XLSX o PDF. */
export interface ExportDashboardRequest extends DashboardFilter {
  readonly format: DashboardExportFormat;
}

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen`.
 *
 * El cliente generado para `/reports/dashboard` declara `Accept: '* / *'` y `responseType: 'blob'`;
 * cuando el cuerpo llega como `Blob`, se lee su texto y se parsea como JSON.
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

/** Desenvuelve respuesta exitosa `Blob` y posibles errores `ProblemDetail` recibidos como `Blob`. */
function unwrapApiResponse<T>(source$: Observable<StrictHttpResponse<T>>): Observable<T> {
  return source$.pipe(
    switchMap((response) => unwrapBody(response.body)),
    unwrapApiError(),
  );
}

/** Convierte una respuesta HTTP binaria en {@link DownloadedDashboardFile}. */
function toDownloadedFile(
  response: HttpResponse<unknown>,
  fallbackFileName: string,
): DownloadedDashboardFile {
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

/** Normaliza el filtro del dashboard omitiendo valores vacíos para respetar los defaults del backend. */
function buildQueryParams(filter?: DashboardFilter): Dashboard$Params {
  const trimmedDate = filter?.date?.trim();
  const validStoreIds = filter?.storeIds?.filter((id) => Number.isInteger(id) && id > 0);
  return {
    date: trimmedDate || undefined,
    storeIds: validStoreIds && validStoreIds.length > 0 ? [...validStoreIds] : undefined,
  };
}

/**
 * Servicio de acceso al dashboard diario y su exportación (`API.md` F.2/F.3, `HU-REP-01`,
 * `HU-REP-10`).
 *
 * Delega las peticiones en las funciones generadas de `src/app/api/fn/reporting` y desenvuelve
 * respuestas y errores `Blob` producidos por `ng-openapi-gen` cuando el OpenAPI declara `* / *`.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Obtiene los KPIs, serie horaria de 24 horas, top 10 de productos, mix de medios de pago y
   * alertas operativas para una fecha y uno o más locales (`GET /api/v1/reports/dashboard`).
   */
  getDashboard(filter?: DashboardFilter, context?: HttpContext): Observable<DashboardResponse> {
    const params = buildQueryParams(filter);
    return unwrapApiResponse(dashboard(this.http, this.apiConfig.rootUrl, params, context));
  }

  /**
   * Descarga el resumen del dashboard en formato Excel (`xlsx`) o `pdf`
   * (`GET /api/v1/reports/dashboard/export`). Requiere `report:export:execute`.
   */
  exportDashboard(
    request: ExportDashboardRequest,
    context?: HttpContext,
  ): Observable<DownloadedDashboardFile> {
    const base = buildQueryParams(request);
    const params: ExportDashboard$Params = {
      ...base,
      format: request.format,
    };
    const fallback = `reporte-dashboard.${request.format}`;
    return exportDashboard(this.http, this.apiConfig.rootUrl, params, context).pipe(
      map((response) => toDownloadedFile(response, fallback)),
      unwrapApiError(),
    );
  }
}
