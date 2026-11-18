import { StockAdjustmentResponse } from '../../../api/models/stock-adjustment-response';
import { StockAdjustmentUserRef } from '../../../api/models/stock-adjustment-user-ref';

/** Estados posibles de un ajuste manual de stock (`API.md` F.3). */
export type AdjustmentStatusCode = NonNullable<StockAdjustmentResponse['status']>;

/** Motivos tipificados de un ajuste manual de stock (`API.md` F.3). */
export type AdjustmentReasonCode = NonNullable<StockAdjustmentResponse['reasonCode']>;

/** Opciones de filtro para el estado del ajuste. */
export const ADJUSTMENT_STATUS_OPTIONS: readonly {
  readonly value: AdjustmentStatusCode;
  readonly label: string;
}[] = [
  { value: 'PENDING_APPROVAL', label: 'Pendiente de aprobación' },
  { value: 'APPROVED', label: 'Aprobado' },
  { value: 'REJECTED', label: 'Rechazado' },
];

/** Opciones tipificadas de motivo para crear y mostrar ajustes. */
export const ADJUSTMENT_REASON_OPTIONS: readonly {
  readonly value: AdjustmentReasonCode;
  readonly label: string;
}[] = [
  { value: 'COUNT_CORRECTION', label: 'Corrección de conteo' },
  { value: 'SYSTEM_ERROR', label: 'Error de sistema' },
  { value: 'FOUND_GOODS', label: 'Mercadería encontrada' },
  { value: 'MISSING_GOODS', label: 'Mercadería faltante' },
  { value: 'LOT_CORRECTION', label: 'Corrección de lote' },
  { value: 'OTHER', label: 'Otro' },
];

const STATUS_LABELS: Readonly<Record<AdjustmentStatusCode, string>> = {
  PENDING_APPROVAL: 'Pendiente de aprobación',
  APPROVED: 'Aprobado',
  REJECTED: 'Rechazado',
};

const REASON_LABELS: Readonly<Record<AdjustmentReasonCode, string>> = {
  COUNT_CORRECTION: 'Corrección de conteo',
  SYSTEM_ERROR: 'Error de sistema',
  FOUND_GOODS: 'Mercadería encontrada',
  MISSING_GOODS: 'Mercadería faltante',
  LOT_CORRECTION: 'Corrección de lote',
  OTHER: 'Otro',
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

/** Devuelve la etiqueta en español de un estado de ajuste. */
export function adjustmentStatusLabel(status: AdjustmentStatusCode | undefined): string {
  return status ? (STATUS_LABELS[status] ?? status) : '—';
}

/** Devuelve la etiqueta en español de un motivo de ajuste. */
export function adjustmentReasonLabel(reasonCode: AdjustmentReasonCode | undefined): string {
  return reasonCode ? (REASON_LABELS[reasonCode] ?? reasonCode) : '—';
}

/** Formatea una marca temporal ISO-8601 en formato local chileno (`dd-mm-aaaa HH:mm`). */
export function formatAdjustmentDateTime(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? '—' : DATETIME_FORMAT.format(parsed);
}

/** Formatea un costo unitario en CLP conservando hasta 4 decimales significativos (A.5). */
export function formatAdjustmentUnitCost(value: number | undefined | null): string {
  if (value === undefined || value === null || !Number.isFinite(value)) {
    return '—';
  }
  return `$${UNIT_COST_FORMAT.format(value)}`;
}

/** Formatea la referencia resumida de usuario mostrando nombre completo o username. */
export function formatUserRef(user: StockAdjustmentUserRef | undefined): string {
  if (!user) {
    return '—';
  }
  return (
    user.fullName?.trim() || user.username?.trim() || (user.id !== undefined ? `#${user.id}` : '—')
  );
}
