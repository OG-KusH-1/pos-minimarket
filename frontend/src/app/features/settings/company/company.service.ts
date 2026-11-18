import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../../api/api-configuration';
import { download } from '../../../api/fn/files/download';
import { upload } from '../../../api/fn/files/upload';
import { get2 } from '../../../api/fn/organization/get-2';
import { update8 } from '../../../api/fn/organization/update-8';
import { CompanyRequest } from '../../../api/models/company-request';
import { CompanyResponse } from '../../../api/models/company-response';
import { FileResponse } from '../../../api/models/file-response';
import { httpForStore, unwrapApiError, unwrapApiResponse } from '../settings-api.utils';

/**
 * Servicio de acceso a la configuración de la empresa y carga de su logo (`HU-CFG-01`, `API.md` F.2).
 *
 * Delega todas las peticiones en las funciones generadas de `src/app/api`.
 */
@Injectable({ providedIn: 'root' })
export class CompanyService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Obtiene los datos actuales de la empresa (`GET /api/v1/company`, global `[G]`). */
  getCompany(context?: HttpContext): Observable<CompanyResponse> {
    return unwrapApiResponse(get2(this.http, this.apiConfig.rootUrl, undefined, context));
  }

  /** Actualiza los datos de la empresa (`PUT /api/v1/company`, global `[G]`). */
  updateCompany(body: CompanyRequest, context?: HttpContext): Observable<CompanyResponse> {
    return unwrapApiResponse(update8(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /**
   * Sube el archivo de logo de la empresa (`POST /api/v1/files?category=COMPANY_LOGO`).
   *
   * Al no ser `[G]`, admite un `storeId` explícito para el header `X-Store-Id` cuando aún no
   * hay local activo seleccionado en el contexto.
   */
  uploadLogo(file: File, storeId?: number | null, context?: HttpContext): Observable<FileResponse> {
    return unwrapApiResponse(
      upload(
        httpForStore(this.http, storeId),
        this.apiConfig.rootUrl,
        {
          category: 'COMPANY_LOGO',
          body: { file },
        },
        context,
      ),
    );
  }

  /** Descarga el contenido binario del logo para previsualizarlo con `Authorization` y `X-Store-Id`. */
  downloadLogo(fileId: number, storeId?: number | null, context?: HttpContext): Observable<Blob> {
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
}
