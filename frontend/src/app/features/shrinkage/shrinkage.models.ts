import { ShrinkageResponse } from '../../api/models/shrinkage-response';
import { ShrinkageUserRef } from '../../api/models/shrinkage-user-ref';

/** Estados posibles de una merma (`API.md` F.3). */
export type ShrinkageStatusCode = NonNullable<ShrinkageResponse['status']>;

/** Tipos posibles de merma (`API.md` F.3). */
export type ShrinkageTypeCode = NonNullable<ShrinkageResponse['shrinkageType']>;

/** Tipos de merma seleccionables al registrar manualmente (excluye `TRANSFER_DIFFERENCE`). */
export type ManualShrinkageTypeCode = Exclude<ShrinkageTypeCode, 'TRANSFER_DIFFERENCE'>;

/** Opciones de filtro para el estado de la merma. */
export const SHRINKAGE_STATUS_OPTIONS: readonly {
  readonly value: ShrinkageStatusCode;
  readonly label: string;
}[] = [
  { value: 'PENDING_APPROVAL', label: 'Pendiente de aprobación' },
  { value: 'APPROVED', label: 'Aprobada' },
  { value: 'REJECTED', label: 'Rechazada' },
];

/** Opciones de tipos de merma manuales para el formulario de registro. */
export const MANUAL_SHRINKAGE_TYPE_OPTIONS: readonly {
  readonly value: ManualShrinkageTypeCode;
  readonly label: string;
}[] = [
  { value: 'EXPIRED', label: 'Vencido' },
  { value: 'DAMAGED', label: 'Dañado' },
  { value: 'BROKEN', label: 'Quebrado / roto' },
  { value: 'THEFT_LOSS', label: 'Hurto / extravío' },
  { value: 'INTERNAL_CONSUMPTION', label: 'Consumo interno' },
  { value: 'RECEIVING_ERROR', label: 'Error de recepción' },
];

/** Opciones completas de tipos de merma para filtros en bandeja. */
export const SHRINKAGE_TYPE_OPTIONS: readonly {
  readonly value: ShrinkageTypeCode;
  readonly label: string;
}[] = [
  ...MANUAL_SHRINKAGE_TYPE_OPTIONS,
  { value: 'TRANSFER_DIFFERENCE', label: 'Diferencia en transferencia' },
];

const STATUS_LABELS: Readonly<Record<ShrinkageStatusCode, string>> = {
  PENDING_APPROVAL: 'Pendiente de aprobación',
  APPROVED: 'Aprobada',
  REJECTED: 'Rechazada',
};

const TYPE_LABELS: Readonly<Record<ShrinkageTypeCode, string>> = {
  EXPIRED: 'Vencido',
  DAMAGED: 'Dañado',
  BROKEN: 'Quebrado / roto',
  THEFT_LOSS: 'Hurto / extravío',
  INTERNAL_CONSUMPTION: 'Consumo interno',
  RECEIVING_ERROR: 'Error de recepción',
  TRANSFER_DIFFERENCE: 'Diferencia en transferencia',
};

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

/** Devuelve la etiqueta en español de un estado de merma. */
export function shrinkageStatusLabel(status: ShrinkageStatusCode | undefined): string {
  return status ? (STATUS_LABELS[status] ?? status) : '—';
}

/** Devuelve la etiqueta en español de un tipo de merma. */
export function shrinkageTypeLabel(type: ShrinkageTypeCode | undefined): string {
  return type ? (TYPE_LABELS[type] ?? type) : '—';
}

/**
 * Indica si un tipo de merma exige motivo obligatorio al registrarse (`API.md` F.3:
 * `THEFT_LOSS` e `INTERNAL_CONSUMPTION`).
 */
export function requiresReasonForType(type: ShrinkageTypeCode | null | undefined): boolean {
  return type === 'THEFT_LOSS' || type === 'INTERNAL_CONSUMPTION';
}

/**
 * Indica si la merma es sistémica (p. ej. `TRANSFER_DIFFERENCE`, `DAMAGED` de devolución sin
 * reingreso, o con `affectsStock === false`).
 */
export function isSystemicShrinkage(row: ShrinkageResponse): boolean {
  return (
    row.affectsStock === false ||
    row.shrinkageType === 'TRANSFER_DIFFERENCE' ||
    row.saleReturnLineId !== undefined ||
    row.transferLineId !== undefined
  );
}

/** Formatea una marca temporal ISO-8601 en formato local chileno (`dd-mm-aaaa HH:mm`). */
export function formatShrinkageDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea un costo unitario en CLP conservando hasta 4 decimales significativos (A.5). */
export function formatShrinkageUnitCost(value: number | undefined | null): string {
  if (value === undefined || value === null || !Number.isFinite(value)) {
    return '—';
  }
  return `$${UNIT_COST_FORMAT.format(value)}`;
}

/** Formatea la referencia resumida de usuario mostrando nombre completo o username. */
export function formatShrinkageUserRef(user: ShrinkageUserRef | undefined): string {
  if (!user) {
    return '—';
  }
  return (
    user.fullName?.trim() || user.username?.trim() || (user.id !== undefined ? `#${user.id}` : '—')
  );
}

/** Extrae el identificador numérico de archivo desde `evidenceUrl` (`/api/v1/files/{id}/content`). */
export function extractFileIdFromUrl(url: string | undefined): number | null {
  if (!url) {
    return null;
  }
  const match = /\/files\/(\d+)\/content/.exec(url);
  if (!match) {
    return null;
  }
  const parsed = Number(match[1]);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}
