import { StockTransferLineResponse } from '../../../api/models/stock-transfer-line-response';
import { StockTransferResponse } from '../../../api/models/stock-transfer-response';
import { StockTransferStoreRef } from '../../../api/models/stock-transfer-store-ref';
import { StockTransferUserRef } from '../../../api/models/stock-transfer-user-ref';
import { QtyUnit } from '../../../shared/pipes';

/** Estados posibles de una transferencia de stock (`API.md` F.3). */
export type TransferStatusCode = NonNullable<StockTransferResponse['status']>;

/** Dirección de la transferencia respecto al local activo (`in` o `out`). */
export type TransferDirectionCode = 'in' | 'out';

/** Opciones de filtro para estado de transferencia. */
export const TRANSFER_STATUS_OPTIONS: readonly {
  readonly value: TransferStatusCode;
  readonly label: string;
}[] = [
  { value: 'DRAFT', label: 'Borrador' },
  { value: 'SENT', label: 'En tránsito' },
  { value: 'RECEIVED', label: 'Recibida' },
  { value: 'CANCELLED', label: 'Cancelada' },
];

/** Opciones de filtro para dirección respecto al local activo. */
export const TRANSFER_DIRECTION_OPTIONS: readonly {
  readonly value: TransferDirectionCode;
  readonly label: string;
}[] = [
  { value: 'out', label: 'Enviadas (salientes)' },
  { value: 'in', label: 'Recibidas (entrantes)' },
];

const STATUS_LABELS: Readonly<Record<TransferStatusCode, string>> = {
  DRAFT: 'Borrador',
  SENT: 'En tránsito',
  RECEIVED: 'Recibida',
  CANCELLED: 'Cancelada',
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

/** Devuelve la etiqueta en español para un estado de transferencia. */
export function transferStatusLabel(status: TransferStatusCode | undefined): string {
  return status ? (STATUS_LABELS[status] ?? status) : '—';
}

/** Determina si el local activo es el origen de la transferencia. */
export function isTransferOrigin(
  transfer: StockTransferResponse,
  activeStoreId: number | null,
): boolean {
  return activeStoreId !== null && transfer.origin?.id === activeStoreId;
}

/** Determina si el local activo es el destino de la transferencia. */
export function isTransferDestination(
  transfer: StockTransferResponse,
  activeStoreId: number | null,
): boolean {
  return activeStoreId !== null && transfer.destination?.id === activeStoreId;
}

/** Devuelve la etiqueta de dirección de la transferencia respecto al local activo. */
export function transferDirectionLabel(
  transfer: StockTransferResponse,
  activeStoreId: number | null,
): 'Saliente' | 'Entrante' | '—' {
  if (isTransferOrigin(transfer, activeStoreId)) {
    return 'Saliente';
  }
  if (isTransferDestination(transfer, activeStoreId)) {
    return 'Entrante';
  }
  return '—';
}

/** Formatea una referencia de local (`código — nombre`). */
export function formatStoreRef(store: StockTransferStoreRef | undefined): string {
  if (!store) {
    return '—';
  }
  if (store.code && store.name) {
    return `${store.code} — ${store.name}`;
  }
  return store.name ?? store.code ?? '—';
}

/** Formatea una referencia de usuario (`fullName` o `username`). */
export function formatUserRef(user: StockTransferUserRef | undefined): string {
  if (!user) {
    return '—';
  }
  return user.fullName || user.username || '—';
}

/** Formatea un instante ISO-8601 en formato local chileno (`dd-mm-aaaa HH:mm`). */
export function formatTransferDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea una fecha ISO (`YYYY-MM-DD`) en formato local chileno (`dd-mm-aaaa`). */
export function formatTransferLocalDate(isoDate: string | undefined): string {
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

/** Traduce la unidad de medida del catálogo (`UNIT` | `KG`) a la unidad de `QtyPipe`. */
export function toTransferQtyUnit(unitOfMeasure: 'UNIT' | 'KG' | undefined): QtyUnit {
  return unitOfMeasure === 'KG' ? 'kg' : 'u';
}

/** Calcula la cantidad faltante de una línea recepcionada (`max(0, qtySent - qtyReceived)`). */
export function lineMissingQty(line: StockTransferLineResponse): number {
  if (line.qtySent === undefined || line.qtyReceived === undefined || line.qtyReceived === null) {
    return 0;
  }
  const diff = line.qtySent - line.qtyReceived;
  return diff > 0 ? Math.round(diff * 1000) / 1000 : 0;
}

/** Indica si una transferencia recepcionada tuvo al menos una línea con faltante. */
export function transferHasDifferences(transfer: StockTransferResponse): boolean {
  return (transfer.lines ?? []).some((line) => lineMissingQty(line) > 0);
}
