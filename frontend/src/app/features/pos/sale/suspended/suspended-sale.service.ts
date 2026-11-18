import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, firstValueFrom, forkJoin, map, of } from 'rxjs';
import { ApiConfiguration } from '../../../../api/api-configuration';
import { get2 } from '../../../../api/fn/catalog/get-2';
import { delete$ } from '../../../../api/fn/sales/delete';
import { list9 } from '../../../../api/fn/sales/list-9';
import { resume } from '../../../../api/fn/sales/resume';
import { suspend } from '../../../../api/fn/sales/suspend';
import { CartQuoteResponse } from '../../../../api/models/cart-quote-response';
import { ProductResponse } from '../../../../api/models/product-response';
import { SuspendSaleRequest } from '../../../../api/models/suspend-sale-request';
import { SuspendedSaleResponse } from '../../../../api/models/suspended-sale-response';
import { withoutErrorNotification } from '../../../../core/http/http-context-tokens';
import { generateUuid } from '../../../../core/http/uuid';
import {
  OVERRIDE_TOKEN_HEADER,
  SupervisorOverrideService,
} from '../../../../shared/ui/override-dialog';
import {
  CartLine,
  POS_CART_STORAGE_KEY,
  PersistedPosCart,
  PosSaleType,
  PosUnitOfMeasure,
} from '../cart/pos-cart.models';
import { PosCartStore } from '../cart/pos-cart.store';
import { unwrapApiError, unwrapApiResponse } from '../cart/pos-http.utils';

/**
 * Servicio POS para suspender, listar, retomar y eliminar carritos en espera en la terminal
 * activa (`HU-POS-04`, `API.md` F.2).
 */
@Injectable({ providedIn: 'root' })
export class SuspendedSaleService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);
  private readonly overrideService = inject(SupervisorOverrideService);

  /** Lista las ventas suspendidas vigentes de la terminal activa (`GET /pos/suspended-sales`). */
  list(context?: HttpContext): Observable<SuspendedSaleResponse[]> {
    return unwrapApiResponse(list9(this.http, this.apiConfig.rootUrl, undefined, context));
  }

  /** Suspende el carrito actual de la terminal (`POST /pos/suspended-sales`). */
  suspend(request: SuspendSaleRequest, context?: HttpContext): Observable<SuspendedSaleResponse> {
    return unwrapApiResponse(
      suspend(this.http, this.apiConfig.rootUrl, { body: request }, context),
    );
  }

  /** Retoma una venta suspendida y la elimina de la cola (`POST /pos/suspended-sales/{id}/resume`). */
  resume(id: number, context?: HttpContext): Observable<SuspendedSaleResponse> {
    return unwrapApiResponse(resume(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Elimina una venta suspendida (`DELETE /pos/suspended-sales/{id}`).
   * Si pertenece a otro cajero, maneja automáticamente el override `SUSPENDED_SALE_DELETE`.
   */
  delete(id: number, context?: HttpContext): Observable<void> {
    const safeContext = context ?? withoutErrorNotification();
    return this.overrideService.withSupervisorOverride((headers) => {
      const overrideToken = headers[OVERRIDE_TOKEN_HEADER];
      return delete$(
        this.http,
        this.apiConfig.rootUrl,
        {
          id,
          ...(overrideToken ? { 'X-Override-Token': overrideToken } : {}),
        },
        safeContext,
      ).pipe(
        unwrapApiError(),
        map(() => undefined),
      );
    });
  }

  /**
   * Hidrata las líneas de una venta suspendida consultando el catálogo y restaurando el estado
   * completo dentro de {@link PosCartStore} (con nueva `Idempotency-Key` y cotización vigente).
   */
  async restoreIntoCartStore(
    suspended: SuspendedSaleResponse,
    store: PosCartStore,
  ): Promise<CartQuoteResponse | null> {
    const inputLines = suspended.cart?.lines ?? [];
    if (inputLines.length === 0) {
      store.clear();
      return null;
    }

    const productsById = await this.fetchProductsMap(inputLines.map((line) => line.productId));
    const cartLines: CartLine[] = inputLines.map((input) => {
      const product = productsById.get(input.productId);
      const inferredWeight = !Number.isInteger(input.quantity) || input.embeddedPrice !== undefined;
      const saleType: PosSaleType = product?.saleType ?? (inferredWeight ? 'WEIGHT' : 'UNIT');
      const unitOfMeasure: PosUnitOfMeasure =
        product?.unitOfMeasure ?? (saleType === 'WEIGHT' ? 'KG' : 'UNIT');

      return {
        lineId: generateUuid(),
        productId: input.productId,
        sku: product?.sku ?? '',
        name: product?.name ?? `Producto #${input.productId}`,
        saleType,
        unitOfMeasure,
        unitPriceGross: product?.priceGross ?? 0,
        quantity: input.quantity,
        ...(input.scannedCode ? { scannedCode: input.scannedCode } : {}),
        ...(input.embeddedPrice !== undefined ? { embeddedPrice: input.embeddedPrice } : {}),
        ...(input.manualDiscount ? { manualDiscount: input.manualDiscount } : {}),
        restricted: Boolean(product?.restrictedType),
        ...(product?.pack?.factor !== undefined ? { packFactor: product.pack.factor } : {}),
      };
    });

    const persisted: PersistedPosCart = {
      version: 1,
      idempotencyKey: generateUuid(),
      lines: cartLines,
      totalDiscount: suspended.cart?.totalDiscount ?? null,
      ageConfirmed: false,
      customerId: suspended.customerId ?? null,
    };

    try {
      globalThis.localStorage?.setItem(POS_CART_STORAGE_KEY, JSON.stringify(persisted));
    } catch {
      // Si localStorage falla en modo privado/bloqueado, se continúa intentando restaurar.
    }
    store.restoreFromStorage();

    try {
      return await store.requote();
    } catch {
      return null;
    }
  }

  private async fetchProductsMap(
    productIds: readonly number[],
  ): Promise<Map<number, ProductResponse>> {
    const uniqueIds = [...new Set(productIds.filter((id) => Number.isFinite(id)))];
    if (uniqueIds.length === 0) {
      return new Map();
    }

    const requests = uniqueIds.map((id) =>
      unwrapApiResponse(
        get2(this.http, this.apiConfig.rootUrl, { id }, withoutErrorNotification()),
      ).pipe(
        map((product) => [id, product] as const),
        catchError(() => of([id, null] as const)),
      ),
    );

    const entries = await firstValueFrom(forkJoin(requests));
    const mapById = new Map<number, ProductResponse>();
    for (const [id, product] of entries) {
      if (product) {
        mapById.set(id, product);
      }
    }
    return mapById;
  }
}
