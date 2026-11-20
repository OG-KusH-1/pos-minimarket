import { ReplenishmentLineResponse } from '../../api/models/replenishment-line-response';
import { ReplenishmentParamsResponse } from '../../api/models/replenishment-params-response';
import { ReplenishmentRunResponse } from '../../api/models/replenishment-run-response';

export type { ReplenishmentLineResponse, ReplenishmentParamsResponse, ReplenishmentRunResponse };

/** Estado posible de una corrida de reabastecimiento (`API.md` F.3). */
export type RunStatusCode = NonNullable<ReplenishmentRunResponse['status']>;

/** Estado posible de una línea de sugerencia (`API.md` F.3). */
export type LineStatusCode = NonNullable<ReplenishmentLineResponse['status']>;

/** Alcance posible de una fila de parámetros (`API.md` F.3). */
export type ParamScope = NonNullable<ReplenishmentParamsResponse['scope']>;

/** Flag de calidad/ajuste del cálculo de sugerencia (`PLAN.md` C.3/C.6). */
export type LineFlag =
  | 'NEW_PRODUCT'
  | 'SHORT_HISTORY'
  | 'CENSORED_DAYS'
  | 'OUTLIERS_CAPPED'
  | 'SHORT_SHELF_LIFE'
  | 'CAPPED_BY_UPPER'
  | 'ZERO_DEMAND'
  | 'MOQ_APPLIED'
  | 'LEAD_TIME_ZERO';

/** Opciones de filtro de estado de corrida. */
export const RUN_STATUS_OPTIONS: readonly {
  readonly value: RunStatusCode;
  readonly label: string;
}[] = [
  { value: 'RUNNING', label: 'En ejecución' },
  { value: 'COMPLETED', label: 'Completada' },
  { value: 'FAILED', label: 'Fallida' },
];

/** Opciones de filtro de estado de línea de sugerencia. */
export const LINE_STATUS_OPTIONS: readonly {
  readonly value: LineStatusCode;
  readonly label: string;
}[] = [
  { value: 'SUGGESTED', label: 'Sugerida' },
  { value: 'NO_ACTION', label: 'Sin acción' },
  { value: 'NO_SUPPLIER', label: 'Sin proveedor' },
  { value: 'EXCLUDED', label: 'Excluida' },
  { value: 'ACCEPTED', label: 'Aceptada' },
  { value: 'DISCARDED', label: 'Descartada' },
];

/** Opciones de filtro de alcance de parámetros. */
export const PARAM_SCOPE_OPTIONS: readonly {
  readonly value: ParamScope;
  readonly label: string;
}[] = [
  { value: 'GLOBAL', label: 'Global' },
  { value: 'STORE', label: 'Local' },
  { value: 'CATEGORY', label: 'Categoría' },
  { value: 'PRODUCT', label: 'Producto' },
];

/** Opciones de filtro de flag del cálculo de sugerencia (`PLAN.md` C.3/C.6). */
export const LINE_FLAG_OPTIONS: readonly {
  readonly value: LineFlag;
  readonly label: string;
}[] = [
  { value: 'NEW_PRODUCT', label: 'Producto nuevo' },
  { value: 'SHORT_HISTORY', label: 'Historia corta' },
  { value: 'CENSORED_DAYS', label: 'Días censurados' },
  { value: 'OUTLIERS_CAPPED', label: 'Atípicos recortados' },
  { value: 'SHORT_SHELF_LIFE', label: 'Vida útil corta' },
  { value: 'CAPPED_BY_UPPER', label: 'Tope por máximo' },
  { value: 'ZERO_DEMAND', label: 'Demanda cero' },
  { value: 'MOQ_APPLIED', label: 'MOQ aplicado' },
  { value: 'LEAD_TIME_ZERO', label: 'Lead time 0' },
];

/** Etiquetas en español de los flags del cálculo (`PLAN.md` C.3/C.6). */
const FLAG_LABELS: Readonly<Record<LineFlag, string>> = {
  NEW_PRODUCT: 'Producto nuevo',
  SHORT_HISTORY: 'Historia corta',
  CENSORED_DAYS: 'Días censurados',
  OUTLIERS_CAPPED: 'Atípicos recortados',
  SHORT_SHELF_LIFE: 'Vida útil corta',
  CAPPED_BY_UPPER: 'Tope por máximo',
  ZERO_DEMAND: 'Demanda cero',
  MOQ_APPLIED: 'MOQ aplicado',
  LEAD_TIME_ZERO: 'Lead time 0',
};

/** Devuelve la etiqueta en español de un flag del cálculo. */
export function flagLabel(flag: string): string {
  return FLAG_LABELS[flag as LineFlag] ?? flag;
}

/** Devuelve la etiqueta en español del estado de una corrida. */
export function runStatusLabel(status: RunStatusCode | undefined): string {
  switch (status) {
    case 'RUNNING':
      return 'En ejecución';
    case 'COMPLETED':
      return 'Completada';
    case 'FAILED':
      return 'Fallida';
    default:
      return '—';
  }
}

/** Devuelve la etiqueta en español del estado de una línea de sugerencia. */
export function lineStatusLabel(status: LineStatusCode | undefined): string {
  switch (status) {
    case 'SUGGESTED':
      return 'Sugerida';
    case 'NO_ACTION':
      return 'Sin acción';
    case 'NO_SUPPLIER':
      return 'Sin proveedor';
    case 'EXCLUDED':
      return 'Excluida';
    case 'ACCEPTED':
      return 'Aceptada';
    case 'DISCARDED':
      return 'Descartada';
    default:
      return '—';
  }
}

/** Devuelve la etiqueta en español del alcance de una fila de parámetros. */
export function scopeLabel(scope: ParamScope | undefined): string {
  switch (scope) {
    case 'GLOBAL':
      return 'Global';
    case 'STORE':
      return 'Local';
    case 'CATEGORY':
      return 'Categoría';
    case 'PRODUCT':
      return 'Producto';
    default:
      return '—';
  }
}

/**
 * Describe una fila de parámetros en texto corto: el alcance con su clave
 * (local, categoría o producto) cuando corresponde.
 */
export function paramsRowKey(row: ReplenishmentParamsResponse): string {
  const parts: string[] = [];
  if (row.storeId !== undefined && row.storeId !== null) {
    parts.push(`Local ${row.storeId}`);
  }
  if (row.productId !== undefined && row.productId !== null) {
    parts.push(`Producto ${row.productId}`);
  }
  if (row.categoryId !== undefined && row.categoryId !== null) {
    parts.push(`Categoría ${row.categoryId}`);
  }
  return parts.join(' · ');
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

/** Formatea un instante UTC ISO-8601 en horario local (`dd-mm-aaaa HH:mm`). */
export function formatDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea una fecha de negocio (`aaaa-mm-dd`) en `dd-mm-aaaa`. */
export function formatDateOnly(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(`${iso}T00:00:00`);
  return Number.isNaN(parsed.getTime()) ? iso : DATE_FORMAT.format(parsed);
}

/**
 * Resume el conteo de líneas por estado de una corrida como texto compacto,
 * p. ej. «5 sugeridas, 2 sin acción» (solo estados presentes con cantidad > 0).
 */
export function countsSummary(counts: Readonly<Record<string, number>> | undefined): string {
  if (!counts) {
    return '';
  }
  const labels: Readonly<Record<string, string>> = {
    SUGGESTED: 'sugerida(s)',
    NO_ACTION: 'sin acción',
    NO_SUPPLIER: 'sin proveedor',
    EXCLUDED: 'excluida(s)',
    ACCEPTED: 'aceptada(s)',
    DISCARDED: 'descartada(s)',
  };
  const parts = Object.entries(counts)
    .filter(([, count]) => count > 0)
    .map(([status, count]) => `${count} ${labels[status] ?? status.toLowerCase()}`);
  return parts.join(', ');
}

/**
 * Indica si una línea es seleccionable para generar borradores de OC: el backend
 * agrupa SUGGESTED/ACCEPTED por proveedor (`PLAN.md` C.5); las demás no participan.
 */
export function isDraftCandidate(line: ReplenishmentLineResponse): boolean {
  return line.status === 'SUGGESTED' || line.status === 'ACCEPTED';
}
