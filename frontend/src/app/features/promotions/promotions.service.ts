import { HttpContext, HttpClient, HttpErrorResponse } from '@angular/common/http';
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
import { activate3 } from '../../api/fn/promotions/activate-3';
import { create10 } from '../../api/fn/promotions/create-10';
import { deactivate3 } from '../../api/fn/promotions/deactivate-3';
import { get2 } from '../../api/fn/promotions/get-2';
import { list10, List10$Params } from '../../api/fn/promotions/list-10';
import { update8 } from '../../api/fn/promotions/update-8';
import { PageResponsePromotionResponse } from '../../api/models/page-response-promotion-response';
import { PromotionRequest } from '../../api/models/promotion-request';
import { PromotionResponse } from '../../api/models/promotion-response';
import { StrictHttpResponse } from '../../api/strict-http-response';
import { ApiError, isApiError } from '../../core/http/api-error';

/**
 * Desenvuelve el cuerpo de una respuesta generada por `ng-openapi-gen` cuando llega como `Blob`.
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
 * Servicio de administración de promociones (`API.md` F.2, `HU-POS-07`).
 *
 * Consume exclusivamente los endpoints generados bajo `/api/v1/promotions` (CRUD + `activate` /
 * `deactivate`). El endpoint POS `GET /pos/promotions/active` no se expone aquí: pertenece al
 * flujo de venta (`POST /pos/cart/quote` ya aplica las promociones vigentes).
 */
@Injectable({ providedIn: 'root' })
export class PromotionsService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);

  /** Lista promociones paginadas con filtros `active`, `type`, `vigentAt` y `productId`. */
  listPromotions(
    params?: List10$Params,
    context?: HttpContext,
  ): Observable<PageResponsePromotionResponse> {
    return unwrapApiResponse(list10(this.http, this.apiConfig.rootUrl, params, context));
  }

  /** Obtiene el detalle completo de una promoción, incluyendo `version` para edición optimista. */
  getPromotion(id: number, context?: HttpContext): Observable<PromotionResponse> {
    return unwrapApiResponse(get2(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Crea una nueva promoción (el backend valida las reglas por tipo de `MODELO_DATOS.md` B.10). */
  createPromotion(body: PromotionRequest, context?: HttpContext): Observable<PromotionResponse> {
    return unwrapApiResponse(create10(this.http, this.apiConfig.rootUrl, { body }, context));
  }

  /** Actualiza una promoción existente enviando su `version` para concurrencia optimista. */
  updatePromotion(
    id: number,
    body: PromotionRequest,
    context?: HttpContext,
  ): Observable<PromotionResponse> {
    return unwrapApiResponse(update8(this.http, this.apiConfig.rootUrl, { id, body }, context));
  }

  /** Activa una promoción inactiva. */
  activatePromotion(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(activate3(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /** Desactiva una promoción activa. */
  deactivatePromotion(id: number, context?: HttpContext): Observable<void> {
    return unwrapApiResponse(deactivate3(this.http, this.apiConfig.rootUrl, { id }, context));
  }
}
