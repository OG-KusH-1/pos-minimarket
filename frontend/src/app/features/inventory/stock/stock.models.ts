import { StockResponse } from '../../../api/models/stock-response';
import { QtyUnit } from '../../../shared/pipes';

/** Estados posibles de stock en un local (`API.md` F.3). */
export type StockStatusCode = NonNullable<StockResponse['status']>;

/** Metadatos de presentación para cada estado de stock. */
export const STOCK_STATUS_OPTIONS: readonly {
  readonly value: StockStatusCode;
  readonly label: string;
}[] = [
  { value: 'OK', label: 'Normal' },
  { value: 'BELOW_MIN', label: 'Bajo mínimo' },
  { value: 'ZERO', label: 'Sin stock' },
  { value: 'ABOVE_MAX', label: 'Sobre máximo' },
];

/** Devuelve la etiqueta en español de un estado de stock. */
export function stockStatusLabel(status: StockStatusCode | undefined): string {
  switch (status) {
    case 'OK':
      return 'Normal';
    case 'BELOW_MIN':
      return 'Bajo mínimo';
    case 'ZERO':
      return 'Sin stock';
    case 'ABOVE_MAX':
      return 'Sobre máximo';
    default:
      return '—';
  }
}

/** Traduce la unidad de medida del backend (`UNIT` | `KG`) a la unidad de `QtyPipe`. */
export function toQtyUnit(unitOfMeasure: StockResponse['unitOfMeasure']): QtyUnit {
  return unitOfMeasure === 'KG' ? 'kg' : 'u';
}

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

/** Formatea una marca temporal ISO-8601 en formato local chileno (`dd-mm-aaaa HH:mm`). */
export function formatDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea una fecha ISO (`YYYY-MM-DD`) en formato local chileno (`dd-mm-aaaa`). */
export function formatLocalDate(isoDate: string | undefined): string {
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
