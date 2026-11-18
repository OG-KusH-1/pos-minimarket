import { PurchaseOrderResponse } from '../../../api/models/purchase-order-response';
import { PurchaseOrderSummary } from '../../../api/models/purchase-order-summary';

/** Estados posibles de una orden de compra (`API.md` F.3). */
export type OrderStatusCode = NonNullable<PurchaseOrderResponse['status']>;

/** Orígenes posibles de una orden de compra (`API.md` F.3). */
export type OrderOriginCode = NonNullable<PurchaseOrderResponse['origin']>;

/** Opciones de filtro para estado de orden de compra. */
export const ORDER_STATUS_OPTIONS: readonly {
  readonly value: OrderStatusCode;
  readonly label: string;
}[] = [
  { value: 'DRAFT', label: 'Borrador' },
  { value: 'SENT', label: 'Enviada' },
  { value: 'PARTIALLY_RECEIVED', label: 'Parcialmente recibida' },
  { value: 'RECEIVED', label: 'Recibida' },
  { value: 'CANCELLED', label: 'Cancelada' },
];

/** Opciones de filtro para origen de orden de compra. */
export const ORDER_ORIGIN_OPTIONS: readonly {
  readonly value: OrderOriginCode;
  readonly label: string;
}[] = [
  { value: 'MANUAL', label: 'Manual' },
  { value: 'REPLENISHMENT', label: 'Reabastecimiento' },
];

const STATUS_LABELS: Readonly<Record<OrderStatusCode, string>> = {
  DRAFT: 'Borrador',
  SENT: 'Enviada',
  PARTIALLY_RECEIVED: 'Parcialmente recibida',
  RECEIVED: 'Recibida',
  CANCELLED: 'Cancelada',
};

const ORIGIN_LABELS: Readonly<Record<OrderOriginCode, string>> = {
  MANUAL: 'Manual',
  REPLENISHMENT: 'Reabastecimiento',
};

const DATETIME_FORMAT = new Intl.DateTimeFormat('es-CL', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const DATE_FORMAT = new Intl.DateTimeFormat('es-CL', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

/** Devuelve la etiqueta en español para un estado de orden de compra. */
export function orderStatusLabel(status: OrderStatusCode | undefined): string {
  return status ? (STATUS_LABELS[status] ?? status) : '—';
}

/** Devuelve la etiqueta en español para el origen de una orden de compra. */
export function orderOriginLabel(origin: OrderOriginCode | undefined): string {
  return origin ? (ORIGIN_LABELS[origin] ?? origin) : '—';
}

/** Formatea un instante ISO-8601 en formato local chileno (`dd-mm-aaaa HH:mm`). */
export function formatOrderDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea una fecha ISO (`YYYY-MM-DD`) en formato local chileno (`dd-mm-aaaa`). */
export function formatOrderLocalDate(isoDate: string | undefined): string {
  if (!isoDate) {
    return '—';
  }
  const parts = isoDate.split('-').map(Number);
  if (parts.length !== 3 || parts.some((n) => !Number.isFinite(n))) {
    return isoDate;
  }
  const [year, month, day] = parts;
  if (year === undefined || month === undefined || day === undefined) {
    return isoDate;
  }
  return DATE_FORMAT.format(new Date(year, month - 1, day));
}

/** Devuelve la fecha local actual en formato `YYYY-MM-DD`. */
export function todayIsoDate(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/** Indica si una orden de compra está en borrador y puede editarse o enviarse. */
export function isOrderDraft(
  order: Pick<PurchaseOrderSummary | PurchaseOrderResponse, 'status'>,
): boolean {
  return order.status === 'DRAFT';
}

/** Indica si una orden de compra admite recibir mercadería (`SENT` o `PARTIALLY_RECEIVED`). */
export function canReceiveFromOrder(
  order: Pick<PurchaseOrderSummary | PurchaseOrderResponse, 'status'>,
): boolean {
  return order.status === 'SENT' || order.status === 'PARTIALLY_RECEIVED';
}

/** Indica si una orden de compra admite cancelación desde la UI (`DRAFT`, `SENT` o `PARTIALLY_RECEIVED`). */
export function canCancelOrderStatus(
  order: Pick<PurchaseOrderSummary | PurchaseOrderResponse, 'status'>,
): boolean {
  return (
    order.status === 'DRAFT' || order.status === 'SENT' || order.status === 'PARTIALLY_RECEIVED'
  );
}
