import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Tipo de condición de pago de un proveedor (`API.md` F.3). */
export type PaymentTermsType = 'CASH_ON_DELIVERY' | 'CREDIT';

/** Etiquetas en español para las condiciones de pago. */
export const PAYMENT_TERMS_LABELS: Readonly<Record<PaymentTermsType, string>> = {
  CASH_ON_DELIVERY: 'Contado / Contra entrega',
  CREDIT: 'Crédito',
};

/** Días de la semana ISO (1 = lunes ... 7 = domingo) para `deliveryWeekdays`. */
export interface IsoWeekdayOption {
  readonly value: number;
  readonly label: string;
  readonly shortLabel: string;
}

export const ISO_WEEKDAYS: readonly IsoWeekdayOption[] = [
  { value: 1, label: 'Lunes', shortLabel: 'Lun' },
  { value: 2, label: 'Martes', shortLabel: 'Mar' },
  { value: 3, label: 'Miércoles', shortLabel: 'Mié' },
  { value: 4, label: 'Jueves', shortLabel: 'Jue' },
  { value: 5, label: 'Viernes', shortLabel: 'Vie' },
  { value: 6, label: 'Sábado', shortLabel: 'Sáb' },
  { value: 7, label: 'Domingo', shortLabel: 'Dom' },
];

const WEEKDAY_SHORT_MAP = new Map(ISO_WEEKDAYS.map((d) => [d.value, d.shortLabel]));

/** Formatea los días ISO de despacho como texto legible (ej. "Lun, Jue"). */
export function formatDeliveryWeekdays(weekdays: readonly number[] | null | undefined): string {
  if (!weekdays || weekdays.length === 0) {
    return '—';
  }
  return [...weekdays]
    .sort((a, b) => a - b)
    .map((day) => WEEKDAY_SHORT_MAP.get(day) ?? String(day))
    .join(', ');
}

/**
 * Calcula el costo neto por unidad base (`unitCostNet`) a partir del costo por unidad de compra
 * y la cantidad de unidades base contenidas (`HU-PRV-02`). Redondea a 4 decimales (`HALF_UP` /
 * estándar de costos en `PLAN.md` A.5).
 */
export function computeUnitCostNet(
  costPerPurchaseUnit: number | null | undefined,
  unitsPerPurchaseUnit: number | null | undefined,
): number | null {
  if (
    typeof costPerPurchaseUnit !== 'number' ||
    !Number.isFinite(costPerPurchaseUnit) ||
    costPerPurchaseUnit < 0 ||
    typeof unitsPerPurchaseUnit !== 'number' ||
    !Number.isFinite(unitsPerPurchaseUnit) ||
    unitsPerPurchaseUnit <= 0
  ) {
    return null;
  }
  return Math.round((costPerPurchaseUnit / unitsPerPurchaseUnit) * 10000) / 10000;
}

/**
 * Formatea un costo unitario neto en CLP mostrando decimales solo cuando existen (hasta 4).
 */
export function formatUnitCostNet(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  const hasDecimals = Math.abs(value - Math.trunc(value)) > 0.00005;
  return new Intl.NumberFormat('es-CL', {
    style: 'currency',
    currency: 'CLP',
    minimumFractionDigits: hasDecimals ? 2 : 0,
    maximumFractionDigits: hasDecimals ? 4 : 0,
  }).format(value);
}

/** Valida que el texto recortado no esté vacío y cumpla con un largo mínimo. */
export function trimmedMinLength(min: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const raw = control.value;
    if (raw === null || raw === undefined || raw === '') {
      return null;
    }
    if (typeof raw !== 'string') {
      return { minlength: { requiredLength: min, actualLength: 0 } };
    }
    const trimmed = raw.trim();
    if (trimmed.length === 0) {
      return { required: true };
    }
    return trimmed.length < min
      ? { minlength: { requiredLength: min, actualLength: trimmed.length } }
      : null;
  };
}

/** Valida que el valor numérico sea entero dentro del rango `[min, max]`. */
export function integerRangeValidator(min: number, max: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const raw = control.value;
    if (raw === null || raw === undefined || raw === '') {
      return null;
    }
    const num = typeof raw === 'number' ? raw : Number(raw);
    if (!Number.isFinite(num) || !Number.isInteger(num)) {
      return { integer: true };
    }
    if (num < min) {
      return { min: { min, actual: num } };
    }
    if (num > max) {
      return { max: { max, actual: num } };
    }
    return null;
  };
}

/** Valida que el valor numérico sea estrictamente mayor que cero. */
export function positiveNumberValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const raw = control.value;
    if (raw === null || raw === undefined || raw === '') {
      return null;
    }
    const num = typeof raw === 'number' ? raw : Number(raw);
    if (!Number.isFinite(num) || num <= 0) {
      return { positive: true };
    }
    return null;
  };
}
