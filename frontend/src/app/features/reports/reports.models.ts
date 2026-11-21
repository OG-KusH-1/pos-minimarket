import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { AbcRow } from '../../api/models/abc-row';
import { CashDifferenceRow } from '../../api/models/cash-difference-row';
import { CashierSalesRow } from '../../api/models/cashier-sales-row';
import { InventoryValuationRow } from '../../api/models/inventory-valuation-row';
import { MarginRow } from '../../api/models/margin-row';
import { NoRotationRow } from '../../api/models/no-rotation-row';
import { PaymentMethodRow } from '../../api/models/payment-method-row';
import { ProductRankingRow } from '../../api/models/product-ranking-row';
import { PurchasesBySupplierRow } from '../../api/models/purchases-by-supplier-row';
import { SalesReportRow } from '../../api/models/sales-report-row';
import { ShrinkageReportRow } from '../../api/models/shrinkage-report-row';

/** Rango máximo de días inclusive admitido por los reportes (`API.md` F.3). */
export const MAX_REPORT_RANGE_DAYS = 366;

/** Límite máximo de filas en rankings de productos (`API.md` F.2). */
export const MAX_RANKING_LIMIT = 100;

/** Ventana máxima de días sin venta en el reporte de sin rotación (`SalesReportsService`). */
export const MAX_NO_ROTATION_DAYS = 3650;

/** Permisos de reportes y costos según `ROLES_PERMISOS.md` D.1. */
export const REPORT_PERMISSIONS = {
  dashboardRead: 'report:dashboard:read',
  salesRead: 'report:sales:read',
  marginRead: 'report:margin:read',
  inventoryRead: 'report:inventory:read',
  purchasingRead: 'report:purchasing:read',
  shrinkageRead: 'report:shrinkage:read',
  cashRead: 'report:cash:read',
  exportExecute: 'report:export:execute',
  costRead: 'catalog:cost:read',
  categoryRead: 'catalog:product:read',
  userRead: 'security:user:read',
} as const;

/** Identificadores estables de los 12 reportes tabulares de `/reportes`. */
export type ReportKey =
  | 'sales'
  | 'sales-by-cashier'
  | 'sales-by-payment-method'
  | 'product-ranking'
  | 'no-rotation'
  | 'abc'
  | 'margin'
  | 'inventory-valuation'
  | 'purchases-by-supplier'
  | 'shrinkage'
  | 'shrinkage-top-products'
  | 'cash-differences';

/** Formatos de exportación soportados (`API.md` F.1/F.2). */
export type ExportFormat = 'xlsx' | 'pdf';

export type SalesGroupBy = 'day' | 'hour' | 'weekday' | 'category' | 'store';
export type RankingOrder = 'most' | 'least';
export type RankingMetric = 'qty' | 'amount';
export type AbcMetric = 'amount' | 'qty' | 'margin';
export type MarginGroupBy = 'product' | 'category' | 'brand' | 'day';
export type InventoryValuationGroupBy = 'category' | 'store' | 'product';
export type ShrinkageGroupBy = 'type' | 'product' | 'category' | 'store' | 'month';

/** Metadatos de un reporte visible en la pantalla `/reportes`. */
export interface ReportDefinition {
  readonly key: ReportKey;
  readonly label: string;
  readonly shortLabel: string;
  readonly description: string;
  readonly permission: string;
  readonly requiresDateRange: boolean;
  readonly fallbackFileBase: string;
}

/** Catálogo completo de reportes tabulares en orden operativo (`HU-REP-02..09`, `HU-MER-04..05`). */
export const REPORT_DEFINITIONS: readonly ReportDefinition[] = [
  {
    key: 'sales',
    label: 'Ventas por período',
    shortLabel: 'Ventas',
    description:
      'Ventas netas de devoluciones y anulaciones por día, hora, día de semana, categoría o local.',
    permission: REPORT_PERMISSIONS.salesRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-ventas',
  },
  {
    key: 'sales-by-cashier',
    label: 'Ventas por cajero',
    shortLabel: 'Por cajero',
    description:
      'Desempeño por cajero incluyendo ventas netas, anulaciones, devoluciones y overrides.',
    permission: REPORT_PERMISSIONS.salesRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-ventas-cajero',
  },
  {
    key: 'sales-by-payment-method',
    label: 'Ventas por medio de pago',
    shortLabel: 'Medios de pago',
    description:
      'Distribución neta de reembolsos por efectivo, débito, crédito, transferencia y fiado.',
    permission: REPORT_PERMISSIONS.salesRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-ventas-medio-pago',
  },
  {
    key: 'product-ranking',
    label: 'Ranking de productos',
    shortLabel: 'Ranking productos',
    description:
      'Productos más o menos vendidos por cantidad o venta neta, filtrables por categoría.',
    permission: REPORT_PERMISSIONS.salesRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-ranking-productos',
  },
  {
    key: 'no-rotation',
    label: 'Productos sin rotación',
    shortLabel: 'Sin rotación',
    description:
      'Productos con stock positivo y sin ventas netas en la ventana de días hasta la fecha final.',
    permission: REPORT_PERMISSIONS.inventoryRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-sin-rotacion',
  },
  {
    key: 'abc',
    label: 'Análisis ABC',
    shortLabel: 'Análisis ABC',
    description:
      'Clasificación Pareto (A hasta 80 %, B hasta 95 %, C el resto) por monto, cantidad o margen.',
    permission: REPORT_PERMISSIONS.salesRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-abc',
  },
  {
    key: 'margin',
    label: 'Margen y utilidad',
    shortLabel: 'Margen y utilidad',
    description:
      'Utilidad bruta y margen porcentual calculados con el costo snapshot de cada venta.',
    permission: REPORT_PERMISSIONS.marginRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-margen',
  },
  {
    key: 'inventory-valuation',
    label: 'Valorización de inventario',
    shortLabel: 'Valorización',
    description:
      'Valorización del stock actual a costo promedio ponderado (el rango de fechas es opcional).',
    permission: REPORT_PERMISSIONS.inventoryRead,
    requiresDateRange: false,
    fallbackFileBase: 'reporte-valorizacion-inventario',
  },
  {
    key: 'purchases-by-supplier',
    label: 'Compras por proveedor',
    shortLabel: 'Compras proveedor',
    description:
      'Órdenes emitidas, recepciones confirmadas, lead time real y tasa de cumplimiento (fill rate).',
    permission: REPORT_PERMISSIONS.purchasingRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-compras-proveedor',
  },
  {
    key: 'shrinkage',
    label: 'Reporte de mermas',
    shortLabel: 'Mermas',
    description:
      'Mermas aprobadas en cantidad, costo y porcentaje sobre las ventas netas del período.',
    permission: REPORT_PERMISSIONS.shrinkageRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-mermas',
  },
  {
    key: 'shrinkage-top-products',
    label: 'Top productos con mayor merma',
    shortLabel: 'Top mermas',
    description: 'Ranking de productos ordenados por costo acumulado de mermas aprobadas.',
    permission: REPORT_PERMISSIONS.shrinkageRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-mermas-top-productos',
  },
  {
    key: 'cash-differences',
    label: 'Diferencias de caja',
    shortLabel: 'Diferencias caja',
    description: 'Turnos de caja cerrados con monto esperado, contado, diferencia y aprobador.',
    permission: REPORT_PERMISSIONS.cashRead,
    requiresDateRange: true,
    fallbackFileBase: 'reporte-diferencias-caja',
  },
];

export interface SelectOption<T> {
  readonly value: T;
  readonly label: string;
}

export const SALES_GROUP_BY_OPTIONS: readonly SelectOption<SalesGroupBy>[] = [
  { value: 'day', label: 'Día' },
  { value: 'hour', label: 'Hora' },
  { value: 'weekday', label: 'Día de semana' },
  { value: 'category', label: 'Categoría' },
  { value: 'store', label: 'Local' },
];

export const RANKING_ORDER_OPTIONS: readonly SelectOption<RankingOrder>[] = [
  { value: 'most', label: 'Más vendidos' },
  { value: 'least', label: 'Menos vendidos' },
];

export const RANKING_METRIC_OPTIONS: readonly SelectOption<RankingMetric>[] = [
  { value: 'qty', label: 'Cantidad' },
  { value: 'amount', label: 'Venta neta ($)' },
];

export const ABC_METRIC_OPTIONS: readonly SelectOption<AbcMetric>[] = [
  { value: 'amount', label: 'Venta neta ($)' },
  { value: 'qty', label: 'Cantidad' },
  { value: 'margin', label: 'Margen bruto' },
];

export const MARGIN_GROUP_BY_OPTIONS: readonly SelectOption<MarginGroupBy>[] = [
  { value: 'product', label: 'Producto' },
  { value: 'category', label: 'Categoría' },
  { value: 'brand', label: 'Marca' },
  { value: 'day', label: 'Día' },
];

export const VALUATION_GROUP_BY_OPTIONS: readonly SelectOption<InventoryValuationGroupBy>[] = [
  { value: 'category', label: 'Categoría' },
  { value: 'store', label: 'Local' },
  { value: 'product', label: 'Producto' },
];

export const SHRINKAGE_GROUP_BY_OPTIONS: readonly SelectOption<ShrinkageGroupBy>[] = [
  { value: 'type', label: 'Tipo de merma' },
  { value: 'product', label: 'Producto' },
  { value: 'category', label: 'Categoría' },
  { value: 'store', label: 'Local' },
  { value: 'month', label: 'Mes' },
];

export const WITH_DIFFERENCE_OPTIONS: readonly SelectOption<boolean | null>[] = [
  { value: null, label: 'Todos los turnos' },
  { value: true, label: 'Solo con diferencia (≠ $0)' },
  { value: false, label: 'Cuadre exacto ($0)' },
];

/** Unión de filas posibles devueltas por los 12 reportes. */
export type ReportRow =
  | SalesReportRow
  | CashierSalesRow
  | PaymentMethodRow
  | ProductRankingRow
  | NoRotationRow
  | AbcRow
  | MarginRow
  | InventoryValuationRow
  | PurchasesBySupplierRow
  | ShrinkageReportRow
  | CashDifferenceRow;

/** Estado normalizado del resultado de un reporte para renderizado uniforme. */
export interface ReportResultState {
  readonly reportKey: ReportKey;
  readonly generatedAt: string | null;
  readonly storeIds: readonly number[];
  readonly rows: readonly ReportRow[];
  readonly totals: ReportRow | null;
  readonly abcMetric?: AbcMetric;
}

/** Definición de una columna para la tabla de resultados del reporte activo. */
export interface ReportColumnDef {
  readonly key: string;
  readonly header: string;
  readonly align?: 'start' | 'end';
  readonly kind?: 'text' | 'abcBadge' | 'difference';
  readonly value: (row: ReportRow) => string;
  readonly totalValue?: (totals: ReportRow) => string;
}

/** Opción plana de categoría para el selector de Ranking de productos. */
export interface CategoryOption {
  readonly id: number;
  readonly label: string;
}

const PAYMENT_METHOD_LABELS: Readonly<Record<string, string>> = {
  CASH: 'Efectivo',
  DEBIT: 'Débito',
  CREDIT: 'Crédito',
  TRANSFER: 'Transferencia',
  CUSTOMER_CREDIT: 'Fiado / Cta. cte.',
  TOTAL: 'Total',
};

const INTEGER_FORMAT = new Intl.NumberFormat('es-CL', {
  maximumFractionDigits: 0,
  useGrouping: true,
});

const CLP_FORMAT = new Intl.NumberFormat('es-CL', {
  maximumFractionDigits: 0,
  useGrouping: true,
});

const COST_FORMAT = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
  useGrouping: true,
});

const QTY_FORMAT = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 3,
  useGrouping: true,
});

const PCT_FORMAT = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  useGrouping: true,
});

const DECIMAL2_FORMAT = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
  useGrouping: true,
});

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

/** Parsea una fecha `YYYY-MM-DD` en timestamp UTC de medianoche, o `null` si es inválida. */
export function parseIsoDateUtcMs(iso: string | null | undefined): number | null {
  if (!iso) {
    return null;
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso.trim());
  if (!match) {
    return null;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const utcMs = Date.UTC(year, month - 1, day);
  const check = new Date(utcMs);
  if (
    check.getUTCFullYear() !== year ||
    check.getUTCMonth() !== month - 1 ||
    check.getUTCDate() !== day
  ) {
    return null;
  }
  return utcMs;
}

/** Calcula los días calendario inclusive entre dos fechas `YYYY-MM-DD`, o `null` si alguna es inválida. */
export function inclusiveDaysBetween(fromIso: string, toIso: string): number | null {
  const fromMs = parseIsoDateUtcMs(fromIso);
  const toMs = parseIsoDateUtcMs(toIso);
  if (fromMs === null || toMs === null) {
    return null;
  }
  const MS_PER_DAY = 86_400_000;
  return Math.round((toMs - fromMs) / MS_PER_DAY) + 1;
}

/** Formatea una instancia `Date` en fecha local `YYYY-MM-DD`. */
export function toLocalIsoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** Suma `deltaDays` días calendario sobre una fecha `YYYY-MM-DD` sin desfase por horario de verano. */
export function addCalendarDays(isoDate: string, deltaDays: number): string {
  const utcMs = parseIsoDateUtcMs(isoDate);
  if (utcMs === null) {
    return isoDate;
  }
  const shifted = new Date(utcMs + deltaDays * 86_400_000);
  const y = shifted.getUTCFullYear();
  const m = String(shifted.getUTCMonth() + 1).padStart(2, '0');
  const d = String(shifted.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** Rango inicial por defecto (últimos 30 días inclusive terminando hoy). */
export function defaultReportDateRange(today: Date = new Date()): { from: string; to: string } {
  const to = toLocalIsoDate(today);
  const from = addCalendarDays(to, -29);
  return { from, to };
}

/**
 * Validador cruzado para el formulario de filtros de reportes (`API.md` F.3, `ReportFilter.java`).
 *
 * Reglas:
 * - Si `isRangeRequired()` es `true`, `from` y `to` son obligatorios.
 * - Si es `false` (valorización de inventario), ambos pueden quedar vacíos, pero si se informa uno
 *   debe informarse el otro (`incompleteRange`).
 * - Con ambas fechas presentes, valida formato `YYYY-MM-DD`, `from <= to` (`rangeInverted`) y
 *   rango inclusive `<= 366` días (`maxRangeExceeded`).
 */
export function reportDateRangeValidator(isRangeRequired: () => boolean): ValidatorFn {
  return (group: AbstractControl): ValidationErrors | null => {
    const rawFrom = String(group.get('from')?.value ?? '').trim();
    const rawTo = String(group.get('to')?.value ?? '').trim();
    const required = isRangeRequired();

    if (!rawFrom && !rawTo) {
      if (!required) {
        return null;
      }
      return { fromRequired: true, toRequired: true };
    }

    if (!rawFrom || !rawTo) {
      if (!required) {
        return { incompleteRange: true };
      }
      return !rawFrom ? { fromRequired: true } : { toRequired: true };
    }

    const fromMs = parseIsoDateUtcMs(rawFrom);
    const toMs = parseIsoDateUtcMs(rawTo);
    if (fromMs === null || toMs === null) {
      return { invalidDateFormat: true };
    }

    if (fromMs > toMs) {
      return { rangeInverted: true };
    }

    const days = inclusiveDaysBetween(rawFrom, rawTo);
    if (days !== null && days > MAX_REPORT_RANGE_DAYS) {
      return {
        maxRangeExceeded: {
          actualDays: days,
          maxDays: MAX_REPORT_RANGE_DAYS,
        },
      };
    }

    return null;
  };
}

/** Validador de enteros dentro de `[min, max]` (para `limit` y `days`). */
export function integerRangeValidator(min: number, max: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;
    if (value === null || value === undefined || value === '') {
      return null;
    }
    const num = Number(value);
    if (!Number.isInteger(num) || num < min || num > max) {
      return { integerRange: { min, max } };
    }
    return null;
  };
}

/** Devuelve el mensaje legible para el primer error de rango de fechas del formulario. */
export function describeDateRangeError(errors: ValidationErrors | null): string | null {
  if (!errors) {
    return null;
  }
  if (errors['fromRequired'] && errors['toRequired']) {
    return 'Debes indicar las fechas Desde y Hasta para consultar este reporte.';
  }
  if (errors['fromRequired']) {
    return 'La fecha Desde es obligatoria.';
  }
  if (errors['toRequired']) {
    return 'La fecha Hasta es obligatoria.';
  }
  if (errors['incompleteRange']) {
    return 'Si ingresas una fecha en valorización, debes indicar tanto Desde como Hasta.';
  }
  if (errors['invalidDateFormat']) {
    return 'Las fechas deben tener un formato válido (AAAA-MM-DD).';
  }
  if (errors['rangeInverted']) {
    return 'La fecha Desde no puede ser posterior a la fecha Hasta.';
  }
  const maxErr = errors['maxRangeExceeded'] as
    { actualDays?: number; maxDays?: number } | undefined;
  if (maxErr) {
    return `El rango máximo permitido es de ${maxErr.maxDays ?? MAX_REPORT_RANGE_DAYS} días (seleccionaste ${maxErr.actualDays ?? ''} días).`;
  }
  return null;
}

/** Traduce un código de medio de pago a etiqueta legible en español. */
export function paymentMethodLabel(method: string | undefined): string {
  if (!method) {
    return '—';
  }
  return PAYMENT_METHOD_LABELS[method] ?? method;
}

/** Formatea enteros (transacciones, conteos, días). */
export function formatInt(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  return INTEGER_FORMAT.format(Math.round(value));
}

/** Formatea montos CLP sin decimales (`$1.234`, `-$500`). */
export function formatClp(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  const rounded = Math.round(Math.abs(value));
  const sign = value < 0 && rounded !== 0 ? '-' : '';
  return `${sign}$${CLP_FORMAT.format(rounded)}`;
}

/** Formatea costos/márgenes/valorización (hasta 2 decimales, `$1.234,50`). */
export function formatCost(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  const absVal = Math.abs(value);
  const roundedCheck = Math.round(absVal * 100);
  const sign = value < 0 && roundedCheck !== 0 ? '-' : '';
  return `${sign}$${COST_FORMAT.format(absVal)}`;
}

/** Formatea cantidades (hasta 3 decimales). */
export function formatQty(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  const formatted = QTY_FORMAT.format(value);
  return formatted === '-0' ? '0' : formatted;
}

/** Formatea porcentajes a 2 decimales (`12,50 %`). */
export function formatPct(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  return `${PCT_FORMAT.format(value)} %`;
}

/** Formatea números con hasta 2 decimales (`lead time`). */
export function formatDecimal2(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  return DECIMAL2_FORMAT.format(value);
}

/** Formatea instante ISO-8601 en fecha/hora local (`dd-mm-aaaa HH:mm`). */
export function formatDateTime(iso: string | null | undefined, emptyText = '—'): string {
  if (!iso) {
    return emptyText;
  }
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? emptyText : DATETIME_FORMAT.format(parsed);
}

/** Formatea fecha de negocio (`YYYY-MM-DD`) en `dd-mm-aaaa`. */
export function formatDateOnly(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const parsed = new Date(`${iso}T00:00:00`);
  return Number.isNaN(parsed.getTime()) ? iso : DATE_FORMAT.format(parsed);
}

/** Opciones para construir la definición de columnas según permisos y parámetros. */
export interface ReportColumnsOptions {
  readonly canReadCosts: boolean;
  readonly abcMetric: AbcMetric;
}

/** Construye las columnas de la tabla y la fila de totales para cada tipo de reporte. */
export function buildReportColumns(
  reportKey: ReportKey,
  options: ReportColumnsOptions,
): readonly ReportColumnDef[] {
  switch (reportKey) {
    case 'sales':
      return [
        {
          key: 'label',
          header: 'Grupo',
          value: (row) => {
            const r = row as SalesReportRow;
            return r.label || r.key || '—';
          },
          totalValue: (totals) => (totals as SalesReportRow).label || 'Total',
        },
        {
          key: 'transactions',
          header: 'Transacciones',
          align: 'end',
          value: (row) => formatInt((row as SalesReportRow).transactions),
          totalValue: (totals) => formatInt((totals as SalesReportRow).transactions),
        },
        {
          key: 'grossTotal',
          header: 'Total bruto',
          align: 'end',
          value: (row) => formatClp((row as SalesReportRow).grossTotal),
          totalValue: (totals) => formatClp((totals as SalesReportRow).grossTotal),
        },
        {
          key: 'netTotal',
          header: 'Total neto',
          align: 'end',
          value: (row) => formatClp((row as SalesReportRow).netTotal),
          totalValue: (totals) => formatClp((totals as SalesReportRow).netTotal),
        },
        {
          key: 'taxTotal',
          header: 'Impuestos',
          align: 'end',
          value: (row) => formatClp((row as SalesReportRow).taxTotal),
          totalValue: (totals) => formatClp((totals as SalesReportRow).taxTotal),
        },
        {
          key: 'discountTotal',
          header: 'Descuentos',
          align: 'end',
          value: (row) => formatClp((row as SalesReportRow).discountTotal),
          totalValue: (totals) => formatClp((totals as SalesReportRow).discountTotal),
        },
        {
          key: 'returnsTotal',
          header: 'Devoluciones',
          align: 'end',
          value: (row) => formatClp((row as SalesReportRow).returnsTotal),
          totalValue: (totals) => formatClp((totals as SalesReportRow).returnsTotal),
        },
        {
          key: 'avgTicket',
          header: 'Ticket promedio',
          align: 'end',
          value: (row) => formatClp((row as SalesReportRow).avgTicket),
          totalValue: (totals) => formatClp((totals as SalesReportRow).avgTicket),
        },
      ];

    case 'sales-by-cashier':
      return [
        {
          key: 'name',
          header: 'Cajero',
          value: (row) => (row as CashierSalesRow).name || '—',
          totalValue: (totals) => (totals as CashierSalesRow).name || 'Total',
        },
        {
          key: 'transactions',
          header: 'Transacciones',
          align: 'end',
          value: (row) => formatInt((row as CashierSalesRow).transactions),
          totalValue: (totals) => formatInt((totals as CashierSalesRow).transactions),
        },
        {
          key: 'total',
          header: 'Total vendido',
          align: 'end',
          value: (row) => formatClp((row as CashierSalesRow).total),
          totalValue: (totals) => formatClp((totals as CashierSalesRow).total),
        },
        {
          key: 'avgTicket',
          header: 'Ticket promedio',
          align: 'end',
          value: (row) => formatClp((row as CashierSalesRow).avgTicket),
          totalValue: (totals) => formatClp((totals as CashierSalesRow).avgTicket),
        },
        {
          key: 'voids',
          header: 'Anulaciones',
          align: 'end',
          value: (row) => formatInt((row as CashierSalesRow).voids),
          totalValue: (totals) => formatInt((totals as CashierSalesRow).voids),
        },
        {
          key: 'returns',
          header: 'Devoluciones',
          align: 'end',
          value: (row) => formatInt((row as CashierSalesRow).returns),
          totalValue: (totals) => formatInt((totals as CashierSalesRow).returns),
        },
        {
          key: 'discountTotal',
          header: 'Descuentos',
          align: 'end',
          value: (row) => formatClp((row as CashierSalesRow).discountTotal),
          totalValue: (totals) => formatClp((totals as CashierSalesRow).discountTotal),
        },
        {
          key: 'overridesCount',
          header: 'Overrides',
          align: 'end',
          value: (row) => formatInt((row as CashierSalesRow).overridesCount),
          totalValue: (totals) => formatInt((totals as CashierSalesRow).overridesCount),
        },
      ];

    case 'sales-by-payment-method':
      return [
        {
          key: 'method',
          header: 'Medio de pago',
          value: (row) => paymentMethodLabel((row as PaymentMethodRow).method),
          totalValue: (totals) =>
            paymentMethodLabel((totals as PaymentMethodRow).method ?? 'TOTAL'),
        },
        {
          key: 'transactions',
          header: 'Transacciones',
          align: 'end',
          value: (row) => formatInt((row as PaymentMethodRow).transactions),
          totalValue: (totals) => formatInt((totals as PaymentMethodRow).transactions),
        },
        {
          key: 'total',
          header: 'Total recaudado',
          align: 'end',
          value: (row) => formatClp((row as PaymentMethodRow).total),
          totalValue: (totals) => formatClp((totals as PaymentMethodRow).total),
        },
        {
          key: 'pct',
          header: 'Participación',
          align: 'end',
          value: (row) => formatPct((row as PaymentMethodRow).pct),
          totalValue: (totals) => formatPct((totals as PaymentMethodRow).pct),
        },
      ];

    case 'product-ranking':
      return [
        {
          key: 'rank',
          header: '#',
          align: 'end',
          value: (row) => formatInt((row as ProductRankingRow).rank),
        },
        {
          key: 'sku',
          header: 'SKU',
          value: (row) => (row as ProductRankingRow).sku || '—',
        },
        {
          key: 'name',
          header: 'Producto',
          value: (row) => (row as ProductRankingRow).name || '—',
        },
        {
          key: 'category',
          header: 'Categoría',
          value: (row) => (row as ProductRankingRow).category || '—',
        },
        {
          key: 'qty',
          header: 'Cantidad',
          align: 'end',
          value: (row) => formatQty((row as ProductRankingRow).qty),
        },
        {
          key: 'netSales',
          header: 'Venta neta',
          align: 'end',
          value: (row) => formatClp((row as ProductRankingRow).netSales),
        },
        {
          key: 'transactions',
          header: 'Transacciones',
          align: 'end',
          value: (row) => formatInt((row as ProductRankingRow).transactions),
        },
      ];

    case 'no-rotation': {
      const cols: ReportColumnDef[] = [
        {
          key: 'sku',
          header: 'SKU',
          value: (row) => (row as NoRotationRow).sku || '—',
        },
        {
          key: 'name',
          header: 'Producto',
          value: (row) => (row as NoRotationRow).name || '—',
        },
        {
          key: 'stock',
          header: 'Stock',
          align: 'end',
          value: (row) => formatQty((row as NoRotationRow).stock),
        },
      ];
      if (options.canReadCosts) {
        cols.push({
          key: 'valuation',
          header: 'Valorización',
          align: 'end',
          value: (row) => formatCost((row as NoRotationRow).valuation),
        });
      }
      cols.push(
        {
          key: 'lastSaleAt',
          header: 'Última venta',
          value: (row) => formatDateTime((row as NoRotationRow).lastSaleAt, 'Nunca vendido'),
        },
        {
          key: 'daysWithoutSale',
          header: 'Días sin venta',
          align: 'end',
          value: (row) => {
            const days = (row as NoRotationRow).daysWithoutSale;
            return days !== undefined && days !== null ? formatInt(days) : 'Nunca';
          },
        },
      );
      return cols;
    }

    case 'abc': {
      const metricHeader =
        options.abcMetric === 'qty'
          ? 'Cantidad'
          : options.abcMetric === 'margin'
            ? 'Margen bruto'
            : 'Venta neta';
      return [
        {
          key: 'sku',
          header: 'SKU',
          value: (row) => (row as AbcRow).sku || '—',
        },
        {
          key: 'name',
          header: 'Producto',
          value: (row) => (row as AbcRow).name || '—',
        },
        {
          key: 'metricValue',
          header: metricHeader,
          align: 'end',
          value: (row) => {
            const val = (row as AbcRow).metricValue;
            if (options.abcMetric === 'qty') {
              return formatQty(val);
            }
            if (options.abcMetric === 'margin') {
              return formatCost(val);
            }
            return formatClp(val);
          },
        },
        {
          key: 'pct',
          header: 'Participación',
          align: 'end',
          value: (row) => formatPct((row as AbcRow).pct),
        },
        {
          key: 'cumulativePct',
          header: '% Acumulado',
          align: 'end',
          value: (row) => formatPct((row as AbcRow).cumulativePct),
        },
        {
          key: 'abcClass',
          header: 'Clase ABC',
          kind: 'abcBadge',
          value: (row) => (row as AbcRow).abcClass || '—',
        },
      ];
    }

    case 'margin':
      return [
        {
          key: 'label',
          header: 'Grupo',
          value: (row) => (row as MarginRow).label || (row as MarginRow).key || '—',
          totalValue: (totals) => (totals as MarginRow).label || 'Total',
        },
        {
          key: 'qty',
          header: 'Cantidad',
          align: 'end',
          value: (row) => formatQty((row as MarginRow).qty),
          totalValue: (totals) => formatQty((totals as MarginRow).qty),
        },
        {
          key: 'netSales',
          header: 'Venta neta',
          align: 'end',
          value: (row) => formatClp((row as MarginRow).netSales),
          totalValue: (totals) => formatClp((totals as MarginRow).netSales),
        },
        {
          key: 'cost',
          header: 'Costo',
          align: 'end',
          value: (row) => formatCost((row as MarginRow).cost),
          totalValue: (totals) => formatCost((totals as MarginRow).cost),
        },
        {
          key: 'grossMargin',
          header: 'Margen bruto',
          align: 'end',
          value: (row) => formatCost((row as MarginRow).grossMargin),
          totalValue: (totals) => formatCost((totals as MarginRow).grossMargin),
        },
        {
          key: 'marginPct',
          header: 'Margen %',
          align: 'end',
          value: (row) => formatPct((row as MarginRow).marginPct),
          totalValue: (totals) => formatPct((totals as MarginRow).marginPct),
        },
      ];

    case 'inventory-valuation':
      return [
        {
          key: 'label',
          header: 'Grupo',
          value: (row) =>
            (row as InventoryValuationRow).label || (row as InventoryValuationRow).key || '—',
          totalValue: (totals) => (totals as InventoryValuationRow).label || 'Total',
        },
        {
          key: 'qty',
          header: 'Stock total',
          align: 'end',
          value: (row) => formatQty((row as InventoryValuationRow).qty),
          totalValue: (totals) => formatQty((totals as InventoryValuationRow).qty),
        },
        {
          key: 'valuation',
          header: 'Valorización',
          align: 'end',
          value: (row) => formatCost((row as InventoryValuationRow).valuation),
          totalValue: (totals) => formatCost((totals as InventoryValuationRow).valuation),
        },
        {
          key: 'pct',
          header: 'Participación',
          align: 'end',
          value: (row) => formatPct((row as InventoryValuationRow).pct),
          totalValue: (totals) => formatPct((totals as InventoryValuationRow).pct),
        },
      ];

    case 'purchases-by-supplier':
      return [
        {
          key: 'name',
          header: 'Proveedor',
          value: (row) => (row as PurchasesBySupplierRow).name || '—',
          totalValue: (totals) => (totals as PurchasesBySupplierRow).name || 'Total',
        },
        {
          key: 'orders',
          header: 'Órdenes',
          align: 'end',
          value: (row) => formatInt((row as PurchasesBySupplierRow).orders),
          totalValue: (totals) => formatInt((totals as PurchasesBySupplierRow).orders),
        },
        {
          key: 'receipts',
          header: 'Recepciones',
          align: 'end',
          value: (row) => formatInt((row as PurchasesBySupplierRow).receipts),
          totalValue: (totals) => formatInt((totals as PurchasesBySupplierRow).receipts),
        },
        {
          key: 'netTotal',
          header: 'Total neto',
          align: 'end',
          value: (row) => formatClp((row as PurchasesBySupplierRow).netTotal),
          totalValue: (totals) => formatClp((totals as PurchasesBySupplierRow).netTotal),
        },
        {
          key: 'avgLeadTimeDays',
          header: 'Lead time prom. (días)',
          align: 'end',
          value: (row) => formatDecimal2((row as PurchasesBySupplierRow).avgLeadTimeDays),
          totalValue: (totals) =>
            formatDecimal2((totals as PurchasesBySupplierRow).avgLeadTimeDays),
        },
        {
          key: 'fillRatePct',
          header: 'Cumplimiento',
          align: 'end',
          value: (row) => formatPct((row as PurchasesBySupplierRow).fillRatePct),
          totalValue: (totals) => formatPct((totals as PurchasesBySupplierRow).fillRatePct),
        },
      ];

    case 'shrinkage':
    case 'shrinkage-top-products': {
      const labelHeader = reportKey === 'shrinkage-top-products' ? 'Producto' : 'Grupo';
      return [
        {
          key: 'label',
          header: labelHeader,
          value: (row) =>
            (row as ShrinkageReportRow).label || (row as ShrinkageReportRow).key || '—',
          totalValue: (totals) => (totals as ShrinkageReportRow).label || 'Total',
        },
        {
          key: 'count',
          header: 'Registros',
          align: 'end',
          value: (row) => formatInt((row as ShrinkageReportRow).count),
          totalValue: (totals) => formatInt((totals as ShrinkageReportRow).count),
        },
        {
          key: 'qty',
          header: 'Cantidad',
          align: 'end',
          value: (row) => formatQty((row as ShrinkageReportRow).qty),
          totalValue: (totals) => formatQty((totals as ShrinkageReportRow).qty),
        },
        {
          key: 'cost',
          header: 'Costo merma',
          align: 'end',
          value: (row) => formatCost((row as ShrinkageReportRow).cost),
          totalValue: (totals) => formatCost((totals as ShrinkageReportRow).cost),
        },
        {
          key: 'pctOfSales',
          header: '% sobre ventas',
          align: 'end',
          value: (row) => formatPct((row as ShrinkageReportRow).pctOfSales),
          totalValue: (totals) => formatPct((totals as ShrinkageReportRow).pctOfSales),
        },
      ];
    }

    case 'cash-differences':
      return [
        {
          key: 'number',
          header: 'Turno',
          value: (row) => (row as CashDifferenceRow).number || '—',
          totalValue: (totals) => (totals as CashDifferenceRow).number || 'Total',
        },
        {
          key: 'businessDate',
          header: 'Fecha',
          value: (row) => formatDateOnly((row as CashDifferenceRow).businessDate),
          totalValue: () => '—',
        },
        {
          key: 'cashier',
          header: 'Cajero',
          value: (row) => (row as CashDifferenceRow).cashier || '—',
          totalValue: () => '—',
        },
        {
          key: 'terminal',
          header: 'Terminal',
          value: (row) => (row as CashDifferenceRow).terminal || '—',
          totalValue: () => '—',
        },
        {
          key: 'expected',
          header: 'Esperado',
          align: 'end',
          value: (row) => formatClp((row as CashDifferenceRow).expected),
          totalValue: (totals) => formatClp((totals as CashDifferenceRow).expected),
        },
        {
          key: 'counted',
          header: 'Contado',
          align: 'end',
          value: (row) => formatClp((row as CashDifferenceRow).counted),
          totalValue: (totals) => formatClp((totals as CashDifferenceRow).counted),
        },
        {
          key: 'difference',
          header: 'Diferencia',
          align: 'end',
          kind: 'difference',
          value: (row) => formatClp((row as CashDifferenceRow).difference),
          totalValue: (totals) => formatClp((totals as CashDifferenceRow).difference),
        },
        {
          key: 'approvedBy',
          header: 'Aprobado por',
          value: (row) => (row as CashDifferenceRow).approvedBy || '—',
          totalValue: () => '—',
        },
      ];
  }
}
