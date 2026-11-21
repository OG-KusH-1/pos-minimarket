import type { DashboardAlerts } from '../../api/models/dashboard-alerts';
import type { DashboardHourlySales } from '../../api/models/dashboard-hourly-sales';
import type { DashboardPaymentMix } from '../../api/models/dashboard-payment-mix';
import type { DashboardTopProduct } from '../../api/models/dashboard-top-product';

/** Formatos de exportación soportados para el dashboard (`API.md` F.2). */
export type DashboardExportFormat = 'xlsx' | 'pdf';

/** Parámetros de consulta aceptados por `GET /api/v1/reports/dashboard` y `/export`. */
export interface DashboardFilter {
  /** Fecha de negocio (`YYYY-MM-DD`); si se omite, el servidor usa hoy en la zona de negocio. */
  readonly date?: string;
  /** Locales a consolidar; si se omite, el servidor usa el local activo (`X-Store-Id`). */
  readonly storeIds?: readonly number[];
}

/** Archivo binario descargado desde `GET /api/v1/reports/dashboard/export`. */
export interface DownloadedDashboardFile {
  readonly blob: Blob;
  readonly fileName: string;
}

/** Punto horario normalizado para las 24 horas del día (`0..23`). */
export interface NormalizedHourlySales {
  readonly hour: number;
  readonly label: string;
  readonly shortLabel: string;
  readonly total: number;
  readonly transactions: number;
}

/** Medio de pago normalizado con etiqueta en español y participación porcentual. */
export interface NormalizedPaymentMix {
  readonly method: string;
  readonly label: string;
  readonly total: number;
  readonly sharePct: number;
}

/** Producto del ranking top 10 normalizado con su posición (`1..10`). */
export interface RankedTopProduct {
  readonly rank: number;
  readonly productId?: number;
  readonly name: string;
  readonly qty: number;
  readonly total: number;
}

/** Descriptor de una alerta operativa del dashboard con su navegación condicional por permiso. */
export interface DashboardAlertDescriptor {
  readonly key: keyof DashboardAlerts;
  readonly label: string;
  readonly description: string;
  readonly count: number;
  readonly severity: 'warning' | 'danger' | 'info';
  readonly icon: string;
  readonly route: string;
  readonly queryParams?: Readonly<Record<string, string>>;
  readonly permission: string;
}

const HOURS_PER_DAY = 24;

const PAYMENT_METHOD_LABELS: Readonly<Record<string, string>> = {
  CASH: 'Efectivo',
  DEBIT: 'Tarjeta de débito',
  CREDIT: 'Tarjeta de crédito',
  TRANSFER: 'Transferencia',
  CUSTOMER_CREDIT: 'Cuenta corriente (fiado)',
};

const DATE_FORMAT = new Intl.DateTimeFormat('es-CL', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

const INTEGER_FORMAT = new Intl.NumberFormat('es-CL', {
  maximumFractionDigits: 0,
  useGrouping: true,
});

const PCT_TWO_DECIMALS = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const PCT_ONE_DECIMAL = new Intl.NumberFormat('es-CL', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

/**
 * Normaliza la serie horaria del backend en exactamente 24 entradas ordenadas (`0..23`),
 * completando con ceros cualquier hora faltante y tolerando datos desordenados o nulos.
 */
export function normalizeHourlySeries(
  series: readonly DashboardHourlySales[] | undefined | null,
): readonly NormalizedHourlySales[] {
  const byHour = new Map<number, { total: number; transactions: number }>();

  for (const entry of series ?? []) {
    const hour = entry.hour;
    if (hour === undefined || !Number.isInteger(hour) || hour < 0 || hour >= HOURS_PER_DAY) {
      continue;
    }
    const prev = byHour.get(hour);
    const total = toFiniteNonNegative(entry.total);
    const transactions = toFiniteNonNegative(entry.transactions);
    byHour.set(hour, {
      total: (prev?.total ?? 0) + total,
      transactions: (prev?.transactions ?? 0) + transactions,
    });
  }

  const normalized: NormalizedHourlySales[] = [];
  for (let hour = 0; hour < HOURS_PER_DAY; hour++) {
    const bucket = byHour.get(hour);
    const shortLabel = String(hour).padStart(2, '0');
    normalized.push({
      hour,
      shortLabel,
      label: `${shortLabel}:00`,
      total: bucket?.total ?? 0,
      transactions: bucket?.transactions ?? 0,
    });
  }
  return normalized;
}

/**
 * Determina la hora punta de ventas en una serie de 24 horas.
 *
 * En empate de monto neto, prioriza la hora con más transacciones y luego la más temprana.
 * Devuelve `null` si ninguna hora registra monto positivo ni transacciones.
 */
export function findPeakHour(
  series: readonly NormalizedHourlySales[],
): NormalizedHourlySales | null {
  let peak: NormalizedHourlySales | null = null;
  for (const point of series) {
    if (point.total <= 0 && point.transactions <= 0) {
      continue;
    }
    if (
      peak === null ||
      point.total > peak.total ||
      (point.total === peak.total && point.transactions > peak.transactions)
    ) {
      peak = point;
    }
  }
  return peak;
}

/** Normaliza la distribución de medios de pago calculando etiqueta legible y participación porcentual. */
export function normalizePaymentMix(
  mix: readonly DashboardPaymentMix[] | undefined | null,
): readonly NormalizedPaymentMix[] {
  const rows = (mix ?? []).map((item) => ({
    method: item.method ?? 'OTHER',
    total: toFiniteNonNegative(item.total),
  }));
  const grandTotal = rows.reduce((sum, row) => sum + row.total, 0);

  return rows.map((row) => ({
    method: row.method,
    label: paymentMethodLabel(row.method),
    total: row.total,
    sharePct: grandTotal > 0 ? (row.total / grandTotal) * 100 : 0,
  }));
}

/** Normaliza la lista de productos top 10 asignando su posición secuencial (`1..10`). */
export function normalizeTopProducts(
  products: readonly DashboardTopProduct[] | undefined | null,
): readonly RankedTopProduct[] {
  return (products ?? []).map((item, idx) => ({
    rank: idx + 1,
    productId: item.productId,
    name: item.name?.trim() || 'Producto sin nombre',
    qty: toFiniteNonNegative(item.qty),
    total: toFiniteNonNegative(item.total),
  }));
}

/** Traduce el código de medio de pago del backend a su etiqueta en español. */
export function paymentMethodLabel(method: string | undefined | null): string {
  if (!method) {
    return 'Otro';
  }
  return PAYMENT_METHOD_LABELS[method] ?? method;
}

/** Construye la lista ordenada de las 7 alertas operativas del dashboard con rutas de acción. */
export function buildAlertDescriptors(
  alerts: DashboardAlerts | undefined | null,
): readonly DashboardAlertDescriptor[] {
  return [
    {
      key: 'zeroStock',
      label: 'Sin stock',
      description: 'Productos con existencia igual a cero',
      count: toFiniteNonNegative(alerts?.zeroStock),
      severity: 'danger',
      icon: 'inventory_2',
      route: '/inventario/stock',
      permission: 'inventory:stock:read',
    },
    {
      key: 'belowMin',
      label: 'Bajo mínimo',
      description: 'Productos en o bajo su stock mínimo',
      count: toFiniteNonNegative(alerts?.belowMin),
      severity: 'warning',
      icon: 'warning_amber',
      route: '/inventario/stock',
      permission: 'inventory:stock:read',
    },
    {
      key: 'expiringLots',
      label: 'Lotes por vencer',
      description: 'Lotes con saldo vencido o próximo a vencer',
      count: toFiniteNonNegative(alerts?.expiringLots),
      severity: 'warning',
      icon: 'event_busy',
      route: '/inventario/lotes',
      queryParams: { tab: 'alerts' },
      permission: 'inventory:expiry:read',
    },
    {
      key: 'pendingCashDifferences',
      label: 'Diferencias de caja',
      description: 'Turnos cerrados sobre tolerancia sin aprobar',
      count: toFiniteNonNegative(alerts?.pendingCashDifferences),
      severity: 'danger',
      icon: 'payments',
      route: '/caja',
      permission: 'cash:session:read',
    },
    {
      key: 'pendingShrinkages',
      label: 'Mermas pendientes',
      description: 'Solicitudes de merma esperando aprobación',
      count: toFiniteNonNegative(alerts?.pendingShrinkages),
      severity: 'info',
      icon: 'delete_sweep',
      route: '/mermas',
      permission: 'shrinkage:record:read',
    },
    {
      key: 'pendingAdjustments',
      label: 'Ajustes pendientes',
      description: 'Ajustes de inventario esperando aprobación',
      count: toFiniteNonNegative(alerts?.pendingAdjustments),
      severity: 'info',
      icon: 'tune',
      route: '/inventario/ajustes',
      permission: 'inventory:adjustment:create',
    },
    {
      key: 'pendingCounts',
      label: 'Tomas pendientes',
      description: 'Tomas físicas enviadas para aprobación',
      count: toFiniteNonNegative(alerts?.pendingCounts),
      severity: 'info',
      icon: 'fact_check',
      route: '/inventario/tomas',
      permission: 'inventory:count:execute',
    },
  ];
}

/** Suma total de alertas operativas activas en el dashboard. */
export function totalAlertsCount(alerts: DashboardAlerts | undefined | null): number {
  if (!alerts) {
    return 0;
  }
  return (
    toFiniteNonNegative(alerts.belowMin) +
    toFiniteNonNegative(alerts.zeroStock) +
    toFiniteNonNegative(alerts.expiringLots) +
    toFiniteNonNegative(alerts.pendingShrinkages) +
    toFiniteNonNegative(alerts.pendingAdjustments) +
    toFiniteNonNegative(alerts.pendingCounts) +
    toFiniteNonNegative(alerts.pendingCashDifferences)
  );
}

/** Formatea una fecha pura (`YYYY-MM-DD`) en formato chileno `dd-mm-aaaa`. */
export function formatLocalDate(isoDate: string | undefined | null): string {
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

/** Formatea contadores enteros con separador de miles chileno (`1.250`). */
export function formatInteger(value: number | undefined | null): string {
  if (value === undefined || value === null || !Number.isFinite(value)) {
    return '0';
  }
  return INTEGER_FORMAT.format(Math.round(value));
}

/** Formatea la variación porcentual respecto al día anterior (p. ej. `+12,50 %`). */
export function formatPctVariation(pct: number | undefined | null): string {
  if (pct === undefined || pct === null || !Number.isFinite(pct)) {
    return 'Sin referencia';
  }
  const sign = pct > 0 ? '+' : pct < 0 ? '-' : '';
  return `${sign}${PCT_TWO_DECIMALS.format(Math.abs(pct))} %`;
}

/** Formatea un porcentaje de participación con un decimal (p. ej. `41,8 %`). */
export function formatPercent(pct: number | undefined | null): string {
  if (pct === undefined || pct === null || !Number.isFinite(pct)) {
    return '0,0 %';
  }
  return `${PCT_ONE_DECIMAL.format(Math.max(0, pct))} %`;
}

/**
 * Extrae el nombre de archivo desde `Content-Disposition` (RFC 6266 / RFC 5987), usando
 * `fallback` si el encabezado no está presente.
 */
export function extractFileName(
  contentDisposition: string | null | undefined,
  fallback: string,
): string {
  if (!contentDisposition) {
    return fallback;
  }
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1].trim());
    } catch {
      return utf8Match[1].trim();
    }
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(contentDisposition);
  return plainMatch?.[1]?.trim() || fallback;
}

function toFiniteNonNegative(value: number | undefined | null): number {
  if (value === undefined || value === null || !Number.isFinite(value)) {
    return 0;
  }
  return Math.max(0, value);
}
