import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../../api/api-configuration';
import { effective } from '../../../api/fn/organization/effective';
import { get3 } from '../../../api/fn/organization/get-3';
import { list2 } from '../../../api/fn/organization/list-2';
import { list16, List16$Params } from '../../../api/fn/organization/list-16';
import { update5 } from '../../../api/fn/organization/update-5';
import { CompanyResponse } from '../../../api/models/company-response';
import { JsonNode } from '../../../api/models/json-node';
import { SettingResponse } from '../../../api/models/setting-response';
import { StoreResponse } from '../../../api/models/store-response';
import { httpForStore, unwrapApiResponse } from '../settings-api.utils';
import { SettingItem, SettingJsonValue, SettingScope } from './settings.metadata';

/**
 * Servicio de acceso a los parámetros de configuración globales y por local (`HU-CFG-04`, `API.md` F.2).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api` y adapta el tipo
 * `JsonNode` generado por OpenAPI a los valores JSON nativos intercambiados por Jackson.
 */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /**
   * Lista los parámetros (`GET /api/v1/settings`) para el local indicado en `storeId` (vía
   * `X-Store-Id`) o para el local activo del contexto si se omite.
   */
  listSettings(
    params?: List16$Params,
    storeId?: number | null,
    context?: HttpContext,
  ): Observable<SettingItem[]> {
    return unwrapApiResponse(
      list16(httpForStore(this.http, storeId), this.apiConfig.rootUrl, params, context),
    ).pipe(map((items) => items.map((item) => this.toSettingItem(item))));
  }

  /**
   * Obtiene el mapa efectivo (`STORE` sobre `GLOBAL`) del local indicado (`GET /api/v1/settings/effective`).
   */
  getEffectiveSettings(
    storeId?: number | null,
    context?: HttpContext,
  ): Observable<Record<string, SettingJsonValue>> {
    return unwrapApiResponse(
      effective(httpForStore(this.http, storeId), this.apiConfig.rootUrl, undefined, context),
    ).pipe(
      map((response) => (response.values ?? {}) as unknown as Record<string, SettingJsonValue>),
    );
  }

  /**
   * Actualiza un parámetro global o del local indicado (`PUT /api/v1/settings/{key}`).
   *
   * Para `scope === 'STORE'`, el servidor resuelve el local destino desde `X-Store-Id`.
   */
  updateSetting(
    key: string,
    scope: SettingScope,
    value: SettingJsonValue,
    storeId?: number | null,
    context?: HttpContext,
  ): Observable<SettingItem> {
    return unwrapApiResponse(
      update5(
        httpForStore(this.http, storeId),
        this.apiConfig.rootUrl,
        {
          key,
          body: {
            scope,
            value: value as unknown as JsonNode,
          },
        },
        context,
      ),
    ).pipe(map((item) => this.toSettingItem(item)));
  }

  /** Lista los locales activos accesibles para poblar el selector de local (`GET /api/v1/stores`). */
  listStores(context?: HttpContext): Observable<StoreResponse[]> {
    return unwrapApiResponse(
      list2(this.http, this.apiConfig.rootUrl, { active: true, size: 200 }, context),
    ).pipe(map((page) => page.content ?? []));
  }

  /** Obtiene los datos de la empresa para mostrar la vista previa realista del ticket. */
  getCompany(context?: HttpContext): Observable<CompanyResponse> {
    return unwrapApiResponse(get3(this.http, this.apiConfig.rootUrl, undefined, context));
  }

  private toSettingItem(raw: SettingResponse): SettingItem {
    return {
      key: raw.key ?? '',
      scope: raw.scope ?? 'GLOBAL',
      storeId: raw.storeId,
      value: (raw.value ?? '') as unknown as SettingJsonValue,
      valueType: raw.valueType ?? 'STRING',
      description: raw.description,
    };
  }
}
