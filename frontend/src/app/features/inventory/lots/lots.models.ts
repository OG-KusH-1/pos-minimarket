import { QtyUnit } from '../../../shared/pipes';

/** Estados posibles de un lote de inventario (`API.md` F.3). */
export type LotStatusCode = 'ACTIVE' | 'DEPLETED';

/** Opciones de filtro para el estado del lote. */
export const LOT_STATUS_OPTIONS: readonly {
  readonly value: LotStatusCode;
  readonly label: string;
}[] = [
  { value: 'ACTIVE', label: 'Con stock' },
  { value: 'DEPLETED', label: 'Agotado' },
];

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

/** Devuelve la etiqueta en español del estado de un lote. */
export function lotStatusLabel(status: string | undefined): string {
  switch (status) {
    case 'ACTIVE':
      return 'Con stock';
    case 'DEPLETED':
      return 'Agotado';
    default:
      return status || '—';
  }
}

/** Traduce la unidad de medida del backend (`UNIT` | `KG`) a la unidad de `QtyPipe`. */
export function toLotQtyUnit(unitOfMeasure: string | undefined): QtyUnit {
  return unitOfMeasure === 'KG' ? 'kg' : 'u';
}

/** Formatea una marca temporal ISO-8601 en formato local chileno (`dd-mm-aaaa HH:mm`). */
export function formatLotDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea una fecha ISO (`YYYY-MM-DD`) en formato local chileno (`dd-mm-aaaa`). */
export function formatLotLocalDate(isoDate: string | undefined): string {
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

/** Formatea los días restantes hasta el vencimiento de un lote en español. */
export function formatDaysToExpiry(days: number | undefined | null): string {
  if (days === undefined || days === null) {
    return 'Sin vencimiento';
  }
  if (days < 0) {
    return `Vencido (${Math.abs(days)} d)`;
  }
  if (days === 0) {
    return 'Vence hoy';
  }
  return days === 1 ? '1 día' : `${days} días`;
}
