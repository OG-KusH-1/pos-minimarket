import { StockMovementResponse } from '../../../api/models/stock-movement-response';

/** Tipos de movimiento del kardex (`API.md` F.3). */
export type StockMovementTypeCode = NonNullable<StockMovementResponse['movementType']>;

/** Orígenes documentales de un movimiento de stock. */
export type StockSourceTypeCode = NonNullable<StockMovementResponse['sourceType']>;

/** Opciones de filtro para tipo de movimiento en español. */
export const MOVEMENT_TYPE_OPTIONS: readonly {
  readonly value: StockMovementTypeCode;
  readonly label: string;
}[] = [
  { value: 'INITIAL', label: 'Carga inicial' },
  { value: 'PURCHASE_RECEIPT', label: 'Recepción de compra' },
  { value: 'SALE', label: 'Venta' },
  { value: 'SALE_VOID', label: 'Anulación de venta' },
  { value: 'SALE_RETURN', label: 'Devolución de venta' },
  { value: 'SHRINKAGE', label: 'Merma' },
  { value: 'ADJUSTMENT_IN', label: 'Ajuste entrada' },
  { value: 'ADJUSTMENT_OUT', label: 'Ajuste salida' },
  { value: 'TRANSFER_OUT', label: 'Transferencia salida' },
  { value: 'TRANSFER_IN', label: 'Transferencia entrada' },
];

const MOVEMENT_LABELS: Readonly<Record<StockMovementTypeCode, string>> = {
  INITIAL: 'Carga inicial',
  PURCHASE_RECEIPT: 'Recepción de compra',
  SALE: 'Venta',
  SALE_VOID: 'Anulación de venta',
  SALE_RETURN: 'Devolución de venta',
  SHRINKAGE: 'Merma',
  ADJUSTMENT_IN: 'Ajuste entrada',
  ADJUSTMENT_OUT: 'Ajuste salida',
  TRANSFER_OUT: 'Transferencia salida',
  TRANSFER_IN: 'Transferencia entrada',
};

const SOURCE_LABELS: Readonly<Record<StockSourceTypeCode, string>> = {
  INITIAL_LOAD: 'Carga inicial',
  GOODS_RECEIPT: 'Recepción',
  SALE: 'Venta',
  SALE_RETURN: 'Devolución',
  SHRINKAGE: 'Merma',
  STOCK_ADJUSTMENT: 'Ajuste',
  INVENTORY_COUNT: 'Toma inventario',
  STOCK_TRANSFER: 'Transferencia',
};

/** Devuelve la etiqueta en español para un tipo de movimiento. */
export function movementTypeLabel(type: StockMovementTypeCode | undefined): string {
  return type ? (MOVEMENT_LABELS[type] ?? type) : '—';
}

/** Devuelve la descripción legible del documento origen del movimiento. */
export function formatMovementSource(
  sourceType: StockSourceTypeCode | undefined,
  sourceId: number | undefined,
): string {
  if (!sourceType) {
    return '—';
  }
  const label = SOURCE_LABELS[sourceType] ?? sourceType;
  return sourceId !== undefined ? `${label} #${sourceId}` : label;
}

const DATETIME_FORMAT = new Intl.DateTimeFormat('es-CL', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const UNIT_COST_FORMAT = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 4,
  useGrouping: true,
});

/** Formatea una fecha/hora ISO-8601 en formato local chileno. */
export function formatKardexDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea un costo unitario en CLP conservando hasta 4 decimales significativos (A.5). */
export function formatUnitCost(value: number | undefined | null): string {
  if (value === undefined || value === null || !Number.isFinite(value)) {
    return '—';
  }
  return `$${UNIT_COST_FORMAT.format(value)}`;
}
