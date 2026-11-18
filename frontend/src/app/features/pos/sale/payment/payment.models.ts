import { CartLineInput } from '../../../../api/models/cart-line-input';
import { DiscountInput } from '../../../../api/models/discount-input';
import { SaleResponse } from '../../../../api/models/sale-response';

/** Medios de pago habilitados en el POS para la Fase 1 (`CUSTOMER_CREDIT` se habilita en T77). */
export type PosElectronicPaymentMethod = 'DEBIT' | 'CREDIT' | 'TRANSFER';

/** Todos los medios de pago disponibles en el modal de cobro POS (Fase 1). */
export type PosPaymentMethod = 'CASH' | PosElectronicPaymentMethod;

/** Autorización de tarjeta aprobada vía `POST /pos/card-payments` pendiente de captura en venta. */
export interface AuthorizedCardPayment {
  readonly id: number;
  readonly method: 'DEBIT' | 'CREDIT';
  readonly amount: number;
  readonly authorizationCode?: string;
  readonly cardBrand?: string;
  readonly last4?: string;
  readonly gatewayReference?: string;
}

/** Pago con transferencia bancaria agregado al cobro mixto. */
export interface TransferPaymentEntry {
  readonly localId: string;
  readonly method: 'TRANSFER';
  readonly amount: number;
  readonly transferReference: string;
}

/** Desglose reactivo del cálculo de redondeo Ley 20.956 y vuelto para efectivo/mixto. */
export interface CashPaymentBreakdown {
  readonly totalGross: number;
  readonly nonCashTotal: number;
  readonly cashDue: number;
  readonly roundingAdjustment: number;
  readonly cashPayable: number;
  readonly totalPayable: number;
  readonly tenderedAmount: number | null;
  readonly changeAmount: number;
  readonly shortfall: number;
  readonly overpaidNonCash: number;
}

/** Datos de entrada para abrir {@link PosPaymentDialog}. */
export interface PosPaymentDialogData {
  readonly cartLines: CartLineInput[];
  readonly totalDiscount: DiscountInput | null;
  readonly customerId: number | null;
  readonly ageConfirmed: boolean;
  readonly totalGross: number;
  readonly idempotencyKey: string;
  readonly initialOverrideTokens: readonly string[];
  readonly cashRoundingEnabled?: boolean;
}

/** Resultado devuelto al completar el cobro desde {@link PosPaymentDialog}. */
export interface PosPaymentDialogResult {
  readonly sale: SaleResponse;
}

/** Etiqueta en español de un medio de pago del POS. */
export function paymentMethodLabel(method: string | undefined): string {
  switch (method) {
    case 'CASH':
      return 'Efectivo';
    case 'DEBIT':
      return 'Débito';
    case 'CREDIT':
      return 'Crédito';
    case 'TRANSFER':
      return 'Transferencia';
    case 'CUSTOMER_CREDIT':
      return 'Fiado';
    default:
      return method ?? 'Otro';
  }
}

/**
 * Calcula el ajuste de redondeo legal de efectivo (Ley 20.956, `PLAN.md` A.5 / `CashRounding.java`).
 *
 * Solo aplica a la porción pagada en efectivo cuando es positiva:
 * si `d = x mod 10`, cuando `d <= 5` resta `d` y cuando `d > 5` suma `10 - d`.
 * Ejemplos: `$1.995 -> -5` (`$1.990`), `$1.996 -> +4` (`$2.000`).
 */
export function calculateCashRoundingAdjustment(cashDue: number, enabled = true): number {
  if (!enabled || !Number.isFinite(cashDue) || cashDue <= 0) {
    return 0;
  }
  const wholeDue = Math.round(cashDue);
  if (wholeDue <= 0) {
    return 0;
  }
  const digit = ((wholeDue % 10) + 10) % 10;
  if (digit === 0) {
    return 0;
  }
  return digit <= 5 ? -digit : 10 - digit;
}

/**
 * Calcula el saldo en efectivo adeudado, el ajuste de redondeo Ley 20.956, el total a pagar
 * y el vuelto para un cobro simple o mixto (`HU-POS-08`, `HU-POS-09`).
 */
export function calculateCashPaymentBreakdown(
  totalGross: number,
  nonCashTotal: number,
  tenderedAmount: number | null,
  roundingEnabled = true,
): CashPaymentBreakdown {
  const safeTotalGross = Math.max(0, Math.round(totalGross));
  const safeNonCash = Math.max(0, Math.round(nonCashTotal));
  const cashDue = Math.max(0, safeTotalGross - safeNonCash);
  const roundingAdjustment = calculateCashRoundingAdjustment(cashDue, roundingEnabled);
  const cashPayable = cashDue > 0 ? Math.max(0, cashDue + roundingAdjustment) : 0;
  const totalPayable = safeTotalGross + roundingAdjustment;
  const safeTendered =
    tenderedAmount !== null && Number.isFinite(tenderedAmount) ? Math.round(tenderedAmount) : null;
  const changeAmount =
    cashPayable > 0 && safeTendered !== null && safeTendered >= cashPayable
      ? safeTendered - cashPayable
      : 0;
  const shortfall = cashPayable > 0 ? Math.max(0, cashPayable - (safeTendered ?? 0)) : 0;
  const overpaidNonCash = safeNonCash > safeTotalGross ? safeNonCash - safeTotalGross : 0;

  return {
    totalGross: safeTotalGross,
    nonCashTotal: safeNonCash,
    cashDue,
    roundingAdjustment,
    cashPayable,
    totalPayable,
    tenderedAmount: safeTendered,
    changeAmount,
    shortfall,
    overpaidNonCash,
  };
}
