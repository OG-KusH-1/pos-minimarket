import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { BarcodeRequest } from '../../../api/models/barcode-request';
import { CategoryResponse } from '../../../api/models/category-response';
import { PriceHistoryResponse } from '../../../api/models/price-history-response';
import { ProductResponse } from '../../../api/models/product-response';
import { ProductSummary } from '../../../api/models/product-summary';

/** Tipos de código de barras admitidos por la API (`API.md` F.3). */
export type BarcodeType = BarcodeRequest['barcodeType'];

/** Tipos de venta admitidos por la API (`API.md` F.3). */
export type SaleType = NonNullable<ProductSummary['saleType']>;

/** Unidades de medida admitidas por la API (`API.md` F.3). */
export type UnitOfMeasure = NonNullable<ProductResponse['unitOfMeasure']>;

/** Tipos de restricción de venta (`HU-CAT-08`). */
export type RestrictedType = NonNullable<ProductResponse['restrictedType']>;

/** Tipos de cambio en historial de precios (`API.md` F.3). */
export type PriceChangeType = NonNullable<PriceHistoryResponse['changeType']>;

/** Patrón de SKU permitido por el backend (`^[A-Za-z0-9._-]{1,40}$`). */
export const SKU_PATTERN = /^[A-Za-z0-9._-]{1,40}$/;

/** Patrón de código de atajo permitido por el backend (`^[A-Z0-9]{1,10}$`). */
export const SHORTCUT_PATTERN = /^[A-Z0-9]{1,10}$/;

/** Patrón general de código de barras (`^[0-9A-Za-z]{3,20}$`). */
export const BARCODE_PATTERN = /^[0-9A-Za-z]{3,20}$/;

/** Tamaño máximo de imagen de producto en bytes (5 MB, `HU-CAT-13`). */
export const MAX_PRODUCT_IMAGE_BYTES = 5 * 1024 * 1024;

/** Etiquetas en español por tipo de venta. */
export const SALE_TYPE_LABELS: Readonly<Record<SaleType, string>> = {
  UNIT: 'Unidad',
  WEIGHT: 'Pesable (kg)',
  PACK: 'Pack',
};

/** Etiquetas en español por unidad de medida. */
export const UOM_LABELS: Readonly<Record<UnitOfMeasure, string>> = {
  UNIT: 'Unidad (u)',
  KG: 'Kilogramo (kg)',
};

/** Etiquetas en español por tipo de restricción. */
export const RESTRICTED_TYPE_LABELS: Readonly<Record<RestrictedType, string>> = {
  ALCOHOL: 'Alcohol (+18)',
  TOBACCO: 'Tabaco (+18)',
  OTHER: 'Otra restricción',
};

/** Etiquetas en español por tipo de código de barras. */
export const BARCODE_TYPE_LABELS: Readonly<Record<BarcodeType, string>> = {
  EAN13: 'EAN-13',
  EAN8: 'EAN-8',
  UPC_A: 'UPC-A',
  INTERNAL: 'Interno',
  SCALE_PLU: 'PLU Balanza',
};

/** Etiquetas en español para el historial de precios y costos. */
export const PRICE_CHANGE_TYPE_LABELS: Readonly<Record<PriceChangeType, string>> = {
  SALE_PRICE: 'Precio de venta',
  COST_MANUAL: 'Costo manual',
  COST_RECEIPT: 'Costo recepción',
  COST_INITIAL: 'Costo inicial',
};

/** Opción plana de categoría para selectores manteniendo la jerarquía de 2 niveles. */
export interface CategoryOption {
  readonly id: number;
  readonly label: string;
  readonly active: boolean;
  readonly isChild: boolean;
}

const COST_FORMAT = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 4,
  useGrouping: true,
});

const MARGIN_FORMAT = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
  useGrouping: false,
});

const FILE_ID_URL_PATTERN = /\/api\/v1\/files\/(\d+)\/content(?:\?.*)?$/;

/**
 * Valida el dígito verificador GS1 para códigos numéricos EAN-13 (13 dígitos) o EAN-8 (8 dígitos).
 *
 * Replica exactamente el algoritmo de `cl.minimarket.pos.shared.rut.Ean`: a los dígitos del cuerpo
 * contados desde la derecha empezando en 1 se les asigna peso 3 si la posición es impar y 1 si es
 * par; `check = (10 - (sum % 10)) % 10`.
 */
export function isValidEanCheckDigit(code: string): boolean {
  if (code.length !== 8 && code.length !== 13) {
    return false;
  }
  if (!/^\d+$/.test(code)) {
    return false;
  }
  const body = code.slice(0, -1);
  let sum = 0;
  for (let i = 0; i < body.length; i++) {
    const digit = Number(body.charAt(i));
    const positionFromRight = body.length - i;
    sum += digit * (positionFromRight % 2 === 1 ? 3 : 1);
  }
  const expected = (10 - (sum % 10)) % 10;
  return expected === Number(code.charAt(code.length - 1));
}

/**
 * Valida formato y dígito verificador de un código de barras según su tipo (`BarcodeValidator.java`).
 */
export function validateBarcodeValue(
  rawCode: string | null | undefined,
  barcodeType: BarcodeType,
): ValidationErrors | null {
  const code = (rawCode ?? '').trim();
  if (!code) {
    return { required: true };
  }
  if (!BARCODE_PATTERN.test(code)) {
    return { barcodeFormat: true };
  }
  switch (barcodeType) {
    case 'EAN13':
      return code.length === 13 && isValidEanCheckDigit(code) ? null : { ean13Invalid: true };
    case 'EAN8':
      return code.length === 8 && isValidEanCheckDigit(code) ? null : { ean8Invalid: true };
    case 'UPC_A':
      return /^\d{12}$/.test(code) && isValidEanCheckDigit(`0${code}`)
        ? null
        : { upcaInvalid: true };
    case 'INTERNAL':
    case 'SCALE_PLU':
      return null;
  }
}

/** Validador Angular reactivo para el control de código de barras según el tipo seleccionado. */
export function barcodeValidator(getType: () => BarcodeType): ValidatorFn {
  return (control: AbstractControl<string>): ValidationErrors | null =>
    validateBarcodeValue(control.value, getType());
}

/** Valida que el texto recortado no esté vacío y tenga al menos `min` caracteres. */
export function trimmedMinLength(min: number): ValidatorFn {
  return (control: AbstractControl<string>): ValidationErrors | null => {
    const trimmed = control.value.trim();
    if (trimmed.length === 0) {
      return { required: true };
    }
    return trimmed.length < min
      ? { minlength: { requiredLength: min, actualLength: trimmed.length } }
      : null;
  };
}

/** Valida que un valor numérico (si está presente) sea entero. */
export const integerValidator: ValidatorFn = (
  control: AbstractControl,
): ValidationErrors | null => {
  const raw: unknown = control.value;
  if (raw === null || raw === undefined || raw === '') {
    return null;
  }
  const num = typeof raw === 'number' ? raw : Number(raw);
  return Number.isInteger(num) ? null : { integer: true };
};

/**
 * Aplana el árbol de categorías de dos niveles en una lista ordenada para `<mat-select>`.
 */
export function flattenCategories(tree: readonly CategoryResponse[]): readonly CategoryOption[] {
  const options: CategoryOption[] = [];
  for (const root of tree) {
    if (root.id === undefined || !root.name) {
      continue;
    }
    options.push({
      id: root.id,
      label: root.name,
      active: root.active !== false,
      isChild: false,
    });
    for (const child of root.children ?? []) {
      if (child.id === undefined || !child.name) {
        continue;
      }
      options.push({
        id: child.id,
        label: `${root.name} › ${child.name}`,
        active: child.active !== false,
        isChild: true,
      });
    }
  }
  return options;
}

/** Extrae el `fileId` numérico desde la `imageUrl` retornada por el backend (`/api/v1/files/{id}/content`). */
export function extractFileIdFromImageUrl(imageUrl: string | null | undefined): number | null {
  if (!imageUrl) {
    return null;
  }
  const match = FILE_ID_URL_PATTERN.exec(imageUrl);
  if (!match?.[1]) {
    return null;
  }
  const parsed = Number(match[1]);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

/** Formatea costos unitarios en CLP permitiendo hasta 4 decimales (`A.5`). */
export function formatUnitCost(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  const sign = value < 0 ? '-' : '';
  return `${sign}$${COST_FORMAT.format(Math.abs(value))}`;
}

/** Formatea porcentaje de margen con hasta 2 decimales (`es-CL`). */
export function formatMarginPct(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  return `${MARGIN_FORMAT.format(value)} %`;
}
