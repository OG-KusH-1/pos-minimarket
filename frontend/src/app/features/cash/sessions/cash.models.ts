import { UserRef } from '../../../api/models/user-ref';
import { isApiError } from '../../../core/http/api-error';

/** Medios de pago soportados en el arqueo de caja (`API.md` F.3). */
export type PaymentMethodCode = 'CASH' | 'DEBIT' | 'CREDIT' | 'TRANSFER' | 'CUSTOMER_CREDIT';

/** Orden estándar de presentación de medios de pago. */
export const ALL_PAYMENT_METHODS: readonly PaymentMethodCode[] = [
  'CASH',
  'DEBIT',
  'CREDIT',
  'TRANSFER',
  'CUSTOMER_CREDIT',
];

/** Tipos de movimiento manual de efectivo en caja. */
export type CashMovementTypeCode = 'CASH_IN' | 'CASH_OUT';

/** Estados posibles de un turno de caja. */
export type CashSessionStatusCode = 'OPEN' | 'CLOSED';

/** Parámetros efectivos de caja leídos desde `/api/v1/settings/effective`. */
export interface CashSettings {
  readonly differenceTolerance: number;
  readonly defaultOpeningAmount: number;
}

/** Valores de respaldo cuando aún no se cargan los parámetros efectivos del local. */
export const DEFAULT_CASH_SETTINGS: CashSettings = {
  differenceTolerance: 500,
  defaultOpeningAmount: 50000,
};

const DATE_TIME_FORMAT = new Intl.DateTimeFormat('es-CL', {
  dateStyle: 'short',
  timeStyle: 'short',
});

const DATE_FORMAT = new Intl.DateTimeFormat('es-CL', {
  dateStyle: 'short',
});

const PAYMENT_METHOD_LABELS: Readonly<Record<PaymentMethodCode, string>> = {
  CASH: 'Efectivo',
  DEBIT: 'Tarjeta de débito',
  CREDIT: 'Tarjeta de crédito',
  TRANSFER: 'Transferencia',
  CUSTOMER_CREDIT: 'Cuenta corriente (fiado)',
};

const MOVEMENT_TYPE_LABELS: Readonly<Record<CashMovementTypeCode, string>> = {
  CASH_IN: 'Ingreso de efectivo',
  CASH_OUT: 'Retiro de efectivo',
};

const SESSION_STATUS_LABELS: Readonly<Record<CashSessionStatusCode, string>> = {
  OPEN: 'Abierto',
  CLOSED: 'Cerrado',
};

/** Etiqueta legible en español para un medio de pago. */
export function paymentMethodLabel(method: PaymentMethodCode | string | undefined | null): string {
  if (!method) {
    return '—';
  }
  return PAYMENT_METHOD_LABELS[method as PaymentMethodCode] ?? method;
}

/** Etiqueta legible en español para un movimiento manual de efectivo. */
export function movementTypeLabel(type: CashMovementTypeCode | string | undefined | null): string {
  if (!type) {
    return '—';
  }
  return MOVEMENT_TYPE_LABELS[type as CashMovementTypeCode] ?? type;
}

/** Etiqueta legible en español para el estado de un turno de caja. */
export function sessionStatusLabel(
  status: CashSessionStatusCode | string | undefined | null,
): string {
  if (!status) {
    return '—';
  }
  return SESSION_STATUS_LABELS[status as CashSessionStatusCode] ?? status;
}

/** Formatea una fecha-hora ISO-8601 a formato local chileno (`es-CL`). */
export function formatCashDateTime(iso: string | undefined | null): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : DATE_TIME_FORMAT.format(date);
}

/** Formatea una fecha `YYYY-MM-DD` (businessDate) a formato local chileno (`es-CL`). */
export function formatCashDate(isoDate: string | undefined | null): string {
  if (!isoDate) {
    return '—';
  }
  const parsed = /^\d{4}-\d{2}-\d{2}$/.test(isoDate)
    ? new Date(`${isoDate}T12:00:00`)
    : new Date(isoDate);
  return Number.isNaN(parsed.getTime()) ? isoDate : DATE_FORMAT.format(parsed);
}

/** Formatea un {@link UserRef} para mostrar nombre completo o usuario. */
export function formatUserDisplay(user: UserRef | undefined | null): string {
  if (!user) {
    return '—';
  }
  if (user.fullName && user.username) {
    return `${user.fullName} (${user.username})`;
  }
  return user.fullName || user.username || (user.id !== undefined ? `#${user.id}` : '—');
}

/** Extrae `cash.difference_tolerance` y `cash.default_opening_amount` desde `settings/effective`. */
export function extractCashSettings(
  values: Readonly<Record<string, unknown>> | undefined | null,
): CashSettings {
  if (!values) {
    return DEFAULT_CASH_SETTINGS;
  }
  const tolerance = parseSettingNumber(
    values['cash.difference_tolerance'],
    DEFAULT_CASH_SETTINGS.differenceTolerance,
  );
  const opening = parseSettingNumber(
    values['cash.default_opening_amount'],
    DEFAULT_CASH_SETTINGS.defaultOpeningAmount,
  );
  return {
    differenceTolerance: Math.max(0, Math.round(tolerance)),
    defaultOpeningAmount: Math.max(0, Math.round(opening)),
  };
}

function parseSettingNumber(raw: unknown, fallback: number): number {
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return raw;
  }
  if (typeof raw === 'string' && raw.trim() !== '') {
    const parsed = Number(raw);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return fallback;
}

const KNOWN_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  CASH_SESSION_ALREADY_OPEN: 'Ya existe un turno de caja abierto para esta terminal o cajero.',
  CASH_SESSION_NOT_OPEN: 'El turno de caja no está abierto.',
  CASH_OUT_EXCEEDS_AVAILABLE: 'El retiro excede el efectivo disponible en caja.',
  SEGREGATION_OF_DUTIES:
    'La aprobación requiere un usuario distinto del cajero dueño del turno (segregación de funciones).',
  OVERRIDE_REQUIRED: 'La operación requiere autorización de un supervisor.',
  OVERRIDE_INVALID: 'La autorización de supervisor no es válida o ya fue utilizada.',
  TERMINAL_INVALID: 'La terminal seleccionada no está disponible o no pertenece al local.',
  ACCESS_DENIED: 'No tienes permiso para realizar esta operación.',
};

/** Traduce un error desconocido (idealmente {@link ApiError}) a un mensaje claro en español. */
export function resolveCashErrorMessage(error: unknown, fallback: string): string {
  if (isApiError(error)) {
    if (error.status >= 500) {
      return error.traceId
        ? `Ocurrió un error inesperado en el servidor. Código: ${error.traceId}`
        : 'Ocurrió un error inesperado en el servidor.';
    }
    const mapped = KNOWN_ERROR_MESSAGES[error.code];
    if (mapped !== undefined) {
      return mapped;
    }
    if (error.message && error.code !== 'UNKNOWN_ERROR') {
      return error.message;
    }
    return fallback;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}
