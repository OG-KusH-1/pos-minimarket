/**
 * DTO de `POST /api/v1/auth/overrides` (API.md F.3) derivados de los modelos generados en
 * `src/app/api/models` y lenguaje claro para la UI.
 *
 * `context` se mantiene como `OverrideContext` (`Record<string, unknown>`) porque el esquema
 * OpenAPI expone `JsonNode` como estructura interna de Jackson.
 */
import type { OverrideRequest as GeneratedOverrideRequest } from '../../../api/models/override-request';
import type { OverrideResponse as GeneratedOverrideResponse } from '../../../api/models/override-response';

/** Acciones que admiten override (`override_action`, MODELO_DATOS). */
export type OverrideAction = GeneratedOverrideRequest['action'];

/** Credencial del supervisor: PIN (4 a 6 dígitos) o clave. */
export type OverrideMethod = GeneratedOverrideRequest['method'];

/** Contexto de la operación (claves según la acción, API.md F.3). El servidor calcula su hash. */
export type OverrideContext = Readonly<Record<string, unknown>>;

/** Cuerpo de `POST /auth/overrides`. */
export interface OverrideRequest extends Omit<GeneratedOverrideRequest, 'context'> {
  readonly context: OverrideContext;
}

/** Respuesta de `POST /auth/overrides`: token de un solo uso (5 min). */
export type OverrideResponse = Readonly<Required<GeneratedOverrideResponse>>;

/** Datos que recibe el diálogo: lo que informó el 422 `OVERRIDE_REQUIRED`. */
export interface OverrideDialogData {
  readonly action: OverrideAction;
  readonly context: OverrideContext;
}

/** Cabecera con la que se repite la operación autorizada (ADR-19). */
export const OVERRIDE_TOKEN_HEADER = 'X-Override-Token';

const ACTION_LABELS: Readonly<Record<OverrideAction, string>> = {
  DISCOUNT_ITEM_OVER_LIMIT: 'Descuento en un producto sobre el límite permitido',
  DISCOUNT_TOTAL_OVER_LIMIT: 'Descuento al total de la venta sobre el límite permitido',
  SALE_VOID: 'Anulación de venta',
  SALE_RETURN: 'Devolución de venta',
  CASH_OUT_OVER_LIMIT: 'Retiro de caja sobre el límite permitido',
  CREDIT_OVER_LIMIT: 'Venta a crédito sobre el límite del cliente',
  SUSPENDED_SALE_DELETE: 'Eliminación de una venta suspendida de otro usuario',
  RECEIPT_REPRINT: 'Reimpresión de comprobante',
  SHRINKAGE_APPROVAL: 'Aprobación de merma',
  STOCK_ADJUSTMENT_APPROVAL: 'Aprobación de ajuste de inventario',
  CASH_DIFFERENCE_APPROVAL: 'Aprobación de diferencia de caja',
};

/** Etiqueta en español de una acción; una acción desconocida se muestra tal cual. */
export function overrideActionLabel(action: string): string {
  return ACTION_LABELS[action as OverrideAction] ?? action;
}

/** Línea "etiqueta: valor" del contexto, lista para mostrar. */
export interface ContextLine {
  readonly label: string;
  readonly value: string;
}

const CONTEXT_LABELS: Readonly<Record<string, string>> = {
  percent: 'Descuento',
  amount: 'Monto',
  lineIndex: 'Línea del carrito',
  productId: 'Producto (id)',
  saleId: 'Venta (id)',
  saleLineId: 'Línea de venta (id)',
  customerId: 'Cliente (id)',
  cashSessionId: 'Sesión de caja (id)',
  entityId: 'Registro (id)',
};

/** Claves técnicas que no aportan al supervisor (p. ej. el hash del carrito). */
const HIDDEN_CONTEXT_KEYS: ReadonlySet<string> = new Set(['cartHash']);

const MONEY_FORMAT = new Intl.NumberFormat('es-CL', {
  maximumFractionDigits: 0,
  useGrouping: true,
});

/**
 * Convierte el contexto del override en líneas legibles: omite claves técnicas y valores vacíos,
 * muestra `percent` como `12 %` y `amount` como pesos (`$1.500`). Las claves sin etiqueta
 * conocida se muestran con su nombre (así una acción nueva del backend sigue siendo visible).
 * El orden de las líneas es el del objeto.
 */
export function describeOverrideContext(context: OverrideContext): readonly ContextLine[] {
  return Object.entries(context)
    .filter(
      ([key, value]) =>
        !HIDDEN_CONTEXT_KEYS.has(key) && value !== null && value !== undefined && value !== '',
    )
    .map(([key, value]) => ({
      label: CONTEXT_LABELS[key] ?? key,
      value: formatContextValue(key, value),
    }));
}

function formatContextValue(key: string, value: unknown): string {
  if (typeof value === 'number' && key === 'amount') {
    return `$${MONEY_FORMAT.format(value)}`;
  }
  if (typeof value === 'number' && key === 'percent') {
    return `${value} %`;
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
