import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiConfiguration } from '../../../api/api-configuration';
import { activate } from '../../../api/fn/organization/activate';
import { activate1 } from '../../../api/fn/organization/activate-1';
import { create1 } from '../../../api/fn/organization/create-1';
import { create2 } from '../../../api/fn/organization/create-2';
import { deactivate } from '../../../api/fn/organization/deactivate';
import { deactivate1 } from '../../../api/fn/organization/deactivate-1';
import { get } from '../../../api/fn/organization/get';
import { list1, List1$Params } from '../../../api/fn/organization/list-1';
import { listByStore } from '../../../api/fn/organization/list-by-store';
import { update } from '../../../api/fn/organization/update';
import { update2 } from '../../../api/fn/organization/update-2';
import { PageResponseStoreResponse } from '../../../api/models/page-response-store-response';
import { StoreRequest } from '../../../api/models/store-request';
import { StoreResponse } from '../../../api/models/store-response';
import { TerminalRequest } from '../../../api/models/terminal-request';
import { TerminalResponse } from '../../../api/models/terminal-response';
import { httpForStore, unwrapApiError, unwrapApiResponse } from '../settings-api.utils';

/**
 * Servicio de acceso a la API de locales y terminales POS (`HU-CFG-02`, `API.md` F.2).
 *
 * Delega todas las llamadas en las funciones generadas de `src/app/api`. Las operaciones de
 * escritura sobre terminales envían explícitamente `X-Store-Id` con el local dueño para coincidir
 * con la validación del servidor (`L-02`).
 */
@Injectable({ providedIn: 'root' })
export class StoresService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista los locales paginados (`GET /api/v1/stores`, `[G]`). */
  listStores(params?: List1$Params, context?: HttpContext): Observable<PageResponseStoreResponse> {
    return unwrapApiResponse(list1(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle de un local (`GET /api/v1/stores/{id}`, `[G]`). */
  getStore(id: number, context?: HttpContext): Observable<StoreResponse> {
    return unwrapApiResponse(get(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Crea un local nuevo (`POST /api/v1/stores`, `[G]`). */
  createStore(body: StoreRequest, context?: HttpContext): Observable<StoreResponse> {
    return unwrapApiResponse(create1(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza un local existente (`PUT /api/v1/stores/{id}`, `[G]`). */
  updateStore(id: number, body: StoreRequest, context?: HttpContext): Observable<StoreResponse> {
    return unwrapApiResponse(update2(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Desactiva un local (`POST /api/v1/stores/{id}/deactivate`, `[G]`). */
  deactivateStore(id: number, context?: HttpContext): Observable<void> {
    return deactivate1(this.http, this.apiConfig.rootUrl, { id }, context).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Reactiva un local (`POST /api/v1/stores/{id}/activate`, `[G]`). */
  activateStore(id: number, context?: HttpContext): Observable<void> {
    return activate1(this.http, this.apiConfig.rootUrl, { id }, context).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Lista las terminales de un local (`GET /api/v1/stores/{storeId}/terminals`, `[G]`). */
  listTerminals(storeId: number, context?: HttpContext): Observable<TerminalResponse[]> {
    return unwrapApiResponse(listByStore(this.http, this.apiConfig.rootUrl, { storeId }, context));
  }

  /** Crea una terminal en el local indicado (`POST /api/v1/stores/{storeId}/terminals`). */
  createTerminal(
    storeId: number,
    body: TerminalRequest,
    context?: HttpContext,
  ): Observable<TerminalResponse> {
    return unwrapApiResponse(
      create2(httpForStore(this.http, storeId), this.apiConfig.rootUrl, { storeId, body }, context),
    );
  }

  /** Actualiza una terminal perteneciente al local indicado (`PUT /api/v1/terminals/{id}`). */
  updateTerminal(
    storeId: number,
    terminalId: number,
    body: TerminalRequest,
    context?: HttpContext,
  ): Observable<TerminalResponse> {
    return unwrapApiResponse(
      update(
        httpForStore(this.http, storeId),
        this.apiConfig.rootUrl,
        { id: terminalId, body },
        context,
      ),
    );
  }

  /** Desactiva una terminal del local indicado (`POST /api/v1/terminals/{id}/deactivate`). */
  deactivateTerminal(storeId: number, terminalId: number, context?: HttpContext): Observable<void> {
    return deactivate(
      httpForStore(this.http, storeId),
      this.apiConfig.rootUrl,
      { id: terminalId },
      context,
    ).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }

  /** Reactiva una terminal del local indicado (`POST /api/v1/terminals/{id}/activate`). */
  activateTerminal(storeId: number, terminalId: number, context?: HttpContext): Observable<void> {
    return activate(
      httpForStore(this.http, storeId),
      this.apiConfig.rootUrl,
      { id: terminalId },
      context,
    ).pipe(
      map(() => undefined),
      unwrapApiError(),
    );
  }
}
