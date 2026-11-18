import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiConfiguration } from '../../../../api/api-configuration';
import { authorize } from '../../../../api/fn/sales/authorize';
import { create6 } from '../../../../api/fn/sales/create-6';
import { voidAuthorization } from '../../../../api/fn/sales/void-authorization';
import { CardAuthorizationResponse } from '../../../../api/models/card-authorization-response';
import { CreateSaleRequest } from '../../../../api/models/create-sale-request';
import { SaleResponse } from '../../../../api/models/sale-response';
import { IDEMPOTENCY_KEY, withIdempotency } from '../../../../core/http/http-context-tokens';
import { generateUuid } from '../../../../core/http/uuid';
import { PosOverrideService } from '../cart/pos-override.service';
import { unwrapApiResponse } from '../cart/pos-http.utils';
import {
  CashPaymentBreakdown,
  calculateCashPaymentBreakdown,
  calculateCashRoundingAdjustment,
} from './payment.models';

/**
 * Servicio POS para cálculos de cobro mixto, redondeo legal chileno (Ley 20.956), vuelto,
 * autorización/anulación de pagos con tarjeta y registro idempotente de ventas (`HU-POS-08`,
 * `HU-POS-09`, `L-05`).
 */
@Injectable({ providedIn: 'root' })
export class PaymentService {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfiguration);
  private readonly overrideService = inject(PosOverrideService);

  /** Calcula el ajuste de redondeo Ley 20.956 sobre la porción en efectivo. */
  calculateCashRoundingAdjustment(cashDue: number, enabled = true): number {
    return calculateCashRoundingAdjustment(cashDue, enabled);
  }

  /** Calcula el resumen completo de cobro mixto, efectivo redondeado y vuelto. */
  calculateCashBreakdown(
    totalGross: number,
    nonCashTotal: number,
    tenderedAmount: number | null,
    roundingEnabled = true,
  ): CashPaymentBreakdown {
    return calculateCashPaymentBreakdown(totalGross, nonCashTotal, tenderedAmount, roundingEnabled);
  }

  /**
   * Autoriza un pago con tarjeta (`DEBIT` o `CREDIT`) en `POST /api/v1/pos/card-payments`
   * usando un `Idempotency-Key` único por intento (`ADR-14`).
   */
  authorizeCardPayment(
    method: 'DEBIT' | 'CREDIT',
    amount: number,
    context?: HttpContext,
  ): Observable<CardAuthorizationResponse> {
    const idempotencyKey = generateUuid();
    const httpContext = withIdempotency(context).set(IDEMPOTENCY_KEY, idempotencyKey);
    return unwrapApiResponse(
      authorize(
        this.http,
        this.apiConfig.rootUrl,
        {
          'Idempotency-Key': idempotencyKey,
          body: {
            method,
            amount: Math.round(amount),
          },
        },
        httpContext,
      ),
    );
  }

  /**
   * Anula una autorización de tarjeta aún no capturada (`POST /api/v1/pos/card-payments/{id}/void`).
   */
  voidCardAuthorization(id: number, context?: HttpContext): Observable<CardAuthorizationResponse> {
    return unwrapApiResponse(voidAuthorization(this.http, this.apiConfig.rootUrl, { id }, context));
  }

  /**
   * Registra la venta (`POST /api/v1/sales`) de forma idempotente y encadenando múltiples
   * autorizaciones de supervisor cuando sea necesario (`L-05`, `X-Override-Token` separado por coma).
   */
  createSale(
    body: CreateSaleRequest,
    idempotencyKey: string,
    initialOverrideTokens: readonly string[] = [],
    context?: HttpContext,
  ): Observable<SaleResponse> {
    const httpContext = withIdempotency(context).set(IDEMPOTENCY_KEY, idempotencyKey);
    return this.overrideService.withMultipleSupervisorOverrides(
      (overrideTokenHeader) =>
        unwrapApiResponse(
          create6(
            this.http,
            this.apiConfig.rootUrl,
            {
              'Idempotency-Key': idempotencyKey,
              ...(overrideTokenHeader ? { 'X-Override-Token': overrideTokenHeader } : {}),
              body,
            },
            httpContext,
          ),
        ),
      initialOverrideTokens,
    );
  }
}
