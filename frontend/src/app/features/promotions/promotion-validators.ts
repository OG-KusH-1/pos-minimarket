import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Tipo de promoción (`API.md` F.2, `MODELO_DATOS.md` B.10). */
export type PromotionType = 'PERCENT' | 'FIXED_AMOUNT' | 'BUY_X_PAY_Y' | 'QUANTITY_PRICE' | 'COMBO';

/** Etiquetas en español para los tipos de promoción. */
export const PROMOTION_TYPE_LABELS: Readonly<Record<PromotionType, string>> = {
  PERCENT: 'Descuento porcentual',
  FIXED_AMOUNT: 'Descuento monto fijo',
  BUY_X_PAY_Y: 'Lleva N, paga M',
  QUANTITY_PRICE: 'Precio especial por cantidad',
  COMBO: 'Combo (precio cerrado)',
};

/** Opciones de tipo de promoción para `mat-select`. */
export const PROMOTION_TYPE_OPTIONS: readonly { value: PromotionType; label: string }[] = (
  Object.keys(PROMOTION_TYPE_LABELS) as PromotionType[]
).map((value) => ({ value, label: PROMOTION_TYPE_LABELS[value] }));

/** Tipo de objetivo de un ítem (`PromotionItemRequest.targetType`). */
export type PromotionTargetType = 'PRODUCT' | 'CATEGORY' | 'BRAND';

/** Etiquetas en español para los tipos de objetivo. */
export const TARGET_TYPE_LABELS: Readonly<Record<PromotionTargetType, string>> = {
  PRODUCT: 'Producto',
  CATEGORY: 'Categoría',
  BRAND: 'Marca',
};

/** Días de la semana ISO (1 = lunes ... 7 = domingo) para `daysOfWeek`. */
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

const DATE_TIME_FORMAT = new Intl.DateTimeFormat('es-CL', {
  dateStyle: 'short',
  timeStyle: 'short',
});

/** Formatea una marca de tiempo UTC ISO-8601 en fecha y hora legibles (zona local). */
export function formatDateTime(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : DATE_TIME_FORMAT.format(parsed);
}

/** Convierte un valor `datetime-local` local (p. ej. `2026-11-19T10:00`) a ISO-8601 UTC. */
export function toUtcIso(localValue: string): string {
  return new Date(localValue).toISOString();
}

/** Convierte una marca de tiempo UTC ISO-8601 al formato `datetime-local` de la zona local. */
export function toDatetimeLocalValue(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) {
    return '';
  }
  const pad = (value: number): string => String(value).padStart(2, '0');
  return (
    `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}` +
    `T${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
  );
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

/** Valida que el valor numérico sea estrictamente mayor que cero (admite decimales). */
export function positiveNumberValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const num = toNumberOrNull(control.value);
    return num !== null && num > 0 ? null : { positive: true };
  };
}

/** Valida que el valor numérico sea un entero mayor que cero (montos CLP, cantidades). */
export function positiveIntegerValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const num = toNumberOrNull(control.value);
    if (num === null) {
      return { positive: true };
    }
    if (!Number.isInteger(num)) {
      return { integer: true };
    }
    return num > 0 ? null : { positive: true };
  };
}

/**
 * Valida `percent` según B.10: debe quedar en el intervalo abierto-cerrado `(0, 100]`.
 */
export function percentValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const num = toNumberOrNull(control.value);
    if (num === null) {
      return null;
    }
    if (num <= 0) {
      return { positive: true };
    }
    return num <= 100 ? null : { max: { max: 100, actual: num } };
  };
}

/**
 * Valida `validTo` > `validFrom` (B.10: `valid_to IS NULL OR valid_to > valid_from`).
 *
 * Se adjunta al control `validTo` y lee a su hermano `validFrom` desde el grupo padre. Ambos
 * valores llegan en formato `datetime-local` local.
 */
export function validToAfterValidFromValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const validTo = control.value;
    if (!validTo || typeof validTo !== 'string') {
      return null;
    }
    const validFrom: string | null = control.parent?.get('validFrom')?.value ?? null;
    if (!validFrom) {
      return null;
    }
    const fromMs = new Date(validFrom).getTime();
    const toMs = new Date(validTo).getTime();
    if (Number.isNaN(fromMs) || Number.isNaN(toMs)) {
      return null;
    }
    return toMs > fromMs ? null : { validToNotAfterValidFrom: true };
  };
}

/**
 * Valida la pareja `timeFrom`/`timeTo` (`API.md` F.2: «ambos o ninguno»).
 *
 * Se adjunta al control `timeTo` y lee a su hermano `timeFrom`.
 */
export function timePairValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const timeTo: string | null = control.value || null;
    const timeFrom: string | null = control.parent?.get('timeFrom')?.value || null;
    return Boolean(timeFrom) !== Boolean(timeTo) ? { timePairIncomplete: true } : null;
  };
}

/**
 * Valida que `storeIds` tenga al menos un local cuando `allStores` sea `false` (`API.md` F.2).
 */
export function storesRequiredValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const allStores: boolean | undefined = control.parent?.get('allStores')?.value;
    if (allStores !== false) {
      return null;
    }
    const storeIds: readonly unknown[] | null = control.value;
    return Array.isArray(storeIds) && storeIds.length > 0 ? null : { storeIdsRequired: true };
  };
}

/**
 * Valida la relación `buyQty > payQty >= 1` de B.10 para `BUY_X_PAY_Y`.
 *
 * Se adjunta al control `payQty` y lee a su hermano `buyQty`.
 */
export function buyPayRelationValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const payQty = toNumberOrNull(control.value);
    if (payQty === null) {
      return null;
    }
    const buyQty = toNumberOrNull(control.parent?.get('buyQty')?.value);
    if (buyQty === null) {
      return null;
    }
    return buyQty > payQty ? null : { payQtyNotLowerThanBuyQty: true };
  };
}

/**
 * Valida el `FormArray` de ítems objetivo (`promotion_items` en B.10):
 *
 * - siempre exige al menos un ítem;
 * - en promociones `COMBO` exige dos o más ítems y todos de tipo `PRODUCT`.
 */
export function promotionItemsValidator(): ValidatorFn {
  return (array: AbstractControl): ValidationErrors | null => {
    const items = array.value as
      readonly { targetType?: PromotionTargetType; targetId?: number | null }[] | null;
    if (!Array.isArray(items) || items.length === 0) {
      return { itemsRequired: true };
    }
    const promotionType: PromotionType | undefined = array.parent?.get('promotionType')?.value;
    if (promotionType === 'COMBO') {
      const productItems = items.filter((item) => item?.targetType === 'PRODUCT');
      if (productItems.length !== items.length) {
        return { comboOnlyProducts: true };
      }
      if (productItems.length < 2) {
        return { comboNeedsTwoProducts: true };
      }
    }
    return null;
  };
}

/** Convierte el valor de un control en número finito, o `null` si está vacío o es inválido. */
function toNumberOrNull(value: unknown): number | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const num = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(num) ? num : null;
}
