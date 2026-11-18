import { DiscountInput } from '../../../../api/models/discount-input';
import { RequiredOverride } from '../../../../api/models/required-override';
import { OverrideAction } from '../../../../shared/ui/override-dialog';

/** Clave de `localStorage` donde se persiste el carrito POS en curso (ADR-25). */
export const POS_CART_STORAGE_KEY = 'pos.sale.cart.v1';

/** Tipo de venta del producto en el POS. */
export type PosSaleType = 'UNIT' | 'WEIGHT' | 'PACK';

/** Unidad de medida del producto en el POS. */
export type PosUnitOfMeasure = 'UNIT' | 'KG';

/** Línea del carrito POS en curso. */
export interface CartLine {
  readonly lineId: string;
  readonly productId: number;
  readonly sku: string;
  readonly name: string;
  readonly saleType: PosSaleType;
  readonly unitOfMeasure: PosUnitOfMeasure;
  readonly unitPriceGross: number;
  readonly quantity: number;
  readonly scannedCode?: string;
  readonly embeddedPrice?: number;
  readonly manualDiscount?: DiscountInput;
  readonly restricted: boolean;
  readonly stockOnHand?: number;
  readonly packFactor?: number;
}

/** Autorización de supervisor ya otorgada para un override requerido del carrito. */
export interface GrantedOverride {
  readonly key: string;
  readonly action: OverrideAction;
  readonly context: Readonly<Record<string, unknown>>;
  readonly overrideToken: string;
}

/** Esquema serializado del carrito en `localStorage` para recuperación tras cierre/recarga. */
export interface PersistedPosCart {
  readonly version: 1;
  readonly idempotencyKey: string;
  readonly lines: readonly CartLine[];
  readonly totalDiscount: DiscountInput | null;
  readonly ageConfirmed: boolean;
  readonly customerId: number | null;
}

/**
 * Construye una clave canónica para identificar un {@link RequiredOverride} y asociarlo a su token.
 * Ordena las llaves del contexto para que no dependa del orden de serialización.
 */
export function buildOverrideKey(
  action: string | undefined,
  context: Readonly<Record<string, unknown>> | undefined,
): string {
  const safeAction = action ?? '';
  if (!context) {
    return `${safeAction}:{}`;
  }
  const ordered = Object.keys(context)
    .sort()
    .reduce<Record<string, unknown>>((acc, key) => {
      acc[key] = context[key];
      return acc;
    }, {});
  return `${safeAction}:${JSON.stringify(ordered)}`;
}

/** Clave canónica de una entrada `RequiredOverride` devuelta por `/pos/cart/quote`. */
export function requiredOverrideKey(required: RequiredOverride): string {
  return buildOverrideKey(required.action, required.context);
}
