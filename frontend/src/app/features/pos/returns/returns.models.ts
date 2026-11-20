import type { LineResponse } from '../../../api/models/line-response';
import type { SaleReturnableLineResponse } from '../../../api/models/sale-returnable-line-response';
import type { SaleReturnableLinesResponse } from '../../../api/models/sale-returnable-lines-response';
import { FieldError, isApiError } from '../../../core/http/api-error';

/**
 * Códigos de motivo de anulación y devolución (`API.md` F.3: `reasonCode` de `VoidSaleRequest`
 * y `SaleReturnRequest`). El backend los persiste y los usa en auditoría y notas de crédito.
 */
export type ReturnReasonCode =
  | 'CUSTOMER_REQUEST'
  | 'DEFECTIVE'
  | 'EXPIRED'
  | 'WRONG_ITEM'
  | 'CASHIER_ERROR'
  | 'PRICE_ERROR'
  | 'OTHER';

/** Opciones de motivo ordenadas por frecuencia de uso en un minimarket. */
export const RETURN_REASON_OPTIONS: readonly {
  readonly code: ReturnReasonCode;
  readonly label: string;
}[] = [
  { code: 'CUSTOMER_REQUEST', label: 'Solicitud del cliente' },
  { code: 'DEFECTIVE', label: 'Producto defectuoso' },
  { code: 'EXPIRED', label: 'Producto vencido' },
  { code: 'WRONG_ITEM', label: 'Producto equivocado' },
  { code: 'CASHIER_ERROR', label: 'Error del cajero' },
  { code: 'PRICE_ERROR', label: 'Error de precio' },
  { code: 'OTHER', label: 'Otro motivo' },
];

/** Etiqueta en español de un código de motivo; un código desconocido se muestra tal cual. */
export function returnReasonLabel(code: string | undefined): string {
  return RETURN_REASON_OPTIONS.find((option) => option.code === code)?.label ?? code ?? '';
}

/** Estados posibles de una venta (`SaleResponse.status`) con su etiqueta en español. */
export function saleStatusLabel(status: string | undefined): string {
  switch (status) {
    case 'COMPLETED':
      return 'Completada';
    case 'VOIDED':
      return 'Anulada';
    case 'PARTIALLY_RETURNED':
      return 'Devuelta parcialmente';
    case 'RETURNED':
      return 'Devuelta';
    default:
      return status ?? '';
  }
}

/** Medio de pago / reembolso con su etiqueta en español. */
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
      return 'Fiado (crédito del cliente)';
    default:
      return method ?? '';
  }
}

/** Tipo de operación (`SaleReturnResponse.returnType`) con su etiqueta en español. */
export function returnTypeLabel(returnType: string | undefined): string {
  switch (returnType) {
    case 'VOID':
      return 'Anulación';
    case 'RETURN':
      return 'Devolución';
    default:
      return returnType ?? '';
  }
}

/** Formatea una fecha ISO-8601 en español chileno (p. ej. `12-11-2026 14:05`). */
export function formatDateTime(iso: string | undefined): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return new Intl.DateTimeFormat('es-CL', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(date);
}

/**
 * Traduce un error del flujo de anulación/devolución a un mensaje claro en español
 * (`HU-POS-14` y `HU-POS-15`).
 *
 * Los códigos de negocio (`VOID_NOT_ALLOWED`, `RETURN_WINDOW_EXPIRED`, …) tienen un texto
 * más útil que el `detail` genérico del ProblemDetail; el resto se delega al mensaje original.
 */
export function resolveReturnsErrorMessage(error: unknown, fallback: string): string {
  if (isApiError(error)) {
    switch (error.code) {
      case 'NOT_FOUND':
        return 'No se encontró la venta indicada en este local.';
      case 'VOID_NOT_ALLOWED':
        return 'La venta no puede anularse: el turno original está cerrado o su estado no lo permite (RN-12).';
      case 'RETURN_WINDOW_EXPIRED':
        return 'El plazo para devolver esta venta ya venció.';
      case 'RETURN_QUANTITY_EXCEEDED':
        return 'La cantidad solicitada supera lo devolvible de una línea. Revisa las cantidades.';
      case 'PAYMENT_MISMATCH':
        return 'El reembolso no cuadra con los medios de pago de la venta. Reintenta sin modificar medios.';
      case 'INVALID_STATE_TRANSITION':
        return 'La venta cambió de estado (quizás fue anulada o devuelta en otra terminal). Vuelve a buscarla.';
      case 'CASH_SESSION_NOT_OPEN':
        return 'Debes abrir un turno de caja antes de anular o devolver.';
      case 'IDEMPOTENCY_CONFLICT':
        return 'Esta operación ya fue registrada con otros datos. Vuelve a buscar la venta para ver el resultado.';
    }
    if (error.status >= 500) {
      return error.traceId
        ? `Ocurrió un error inesperado en el servidor (${error.traceId}).`
        : 'Ocurrió un error inesperado en el servidor.';
    }
    return error.message || fallback;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

/**
 * Línea devolvible enriquecida para la tabla del diálogo: une la línea devolvible del
 * endpoint (`GET /sales/{id}/returnable`) con su línea de venta (`SaleResponse.lines`) para
 * saber el tipo de venta (UNIT/WEIGHT/PACK) y así validar la cantidad: enteros para unidades
 * y packs, hasta 3 decimales para pesables (`API.md` F.3, `qty`).
 */
export interface ReturnLineRow {
  readonly saleLineId: number;
  readonly name: string;
  /** Cantidad original vendida en la línea. */
  readonly quantity: number;
  /** Cantidad ya devuelta en operaciones anteriores. */
  readonly returnedQuantity: number;
  /** Tope real devolvible en esta operación (`returnableQuantity`). */
  readonly returnableQuantity: number;
  /** Reembolso por unidad ya calculado por el backend (descuentos prorrateados, RN-14). */
  readonly unitRefundAmount: number;
  /** Tipo de venta de la línea original. */
  readonly saleType: 'UNIT' | 'WEIGHT' | 'PACK';
  /** `true` cuando la cantidad debe ser entera (UNIT/PACK); los pesables admiten decimales. */
  readonly integerQuantity: boolean;
}

/**
 * Construye las filas devolvibles de la tabla: si una línea devolvible no aparece en el
 * detalle de la venta (datos desincronizados), se asume UNIT para no bloquear la operación.
 */
export function buildReturnLineRows(
  returnable: SaleReturnableLinesResponse,
  saleLines: readonly LineResponse[] | undefined,
): readonly ReturnLineRow[] {
  const linesById = new Map<number, LineResponse>();
  for (const line of saleLines ?? []) {
    if (line.id !== undefined) {
      linesById.set(line.id, line);
    }
  }
  return (returnable.lines ?? []).map((line: SaleReturnableLineResponse) => {
    const saleLine = line.saleLineId !== undefined ? linesById.get(line.saleLineId) : undefined;
    const saleType = saleLine?.saleType ?? 'UNIT';
    return {
      saleLineId: line.saleLineId ?? -1,
      name: line.name ?? saleLine?.name ?? `Línea ${line.saleLineId ?? ''}`,
      quantity: line.quantity ?? 0,
      returnedQuantity: line.returnedQuantity ?? 0,
      returnableQuantity: line.returnableQuantity ?? 0,
      unitRefundAmount: line.unitRefundAmount ?? 0,
      saleType,
      integerQuantity: saleType !== 'WEIGHT',
    };
  });
}

/** Indica si `ApiError` trae `errors[]` por campo (VALIDATION_ERROR) para marcar controles. */
export function apiErrorFieldErrors(error: unknown): readonly FieldError[] {
  return isApiError(error) && error.hasFieldErrors ? error.errors : [];
}
