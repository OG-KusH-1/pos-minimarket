import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DashboardHourlySales } from '../../api/models/dashboard-hourly-sales';
import { ClpPipe } from '../../shared/pipes';
import {
  NormalizedHourlySales,
  findPeakHour,
  formatInteger,
  formatLocalDate,
  normalizeHourlySeries,
} from './dashboard.models';

/** Altura visual mínima en porcentaje para destacar horas con venta distinta de cero frente a `$0`. */
const MIN_NON_ZERO_BAR_PCT = 4;

/**
 * Componente de visualización de ventas por hora (`00:00`–`23:00`) para el dashboard diario
 * (`HU-REP-01`, `API.md` F.3).
 *
 * Normaliza la serie recibida en 24 bloques horarios, destaca la hora punta, permite inspeccionar
 * cada hora con mouse o teclado y alternar hacia una vista tabular accesible.
 */
@Component({
  selector: 'pos-hourly-sales-chart',
  imports: [ClpPipe, MatButtonModule, MatIconModule, MatTableModule, MatTooltipModule],
  templateUrl: './hourly-sales-chart.html',
  styleUrl: './hourly-sales-chart.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HourlySalesChart {
  /** Serie horaria devuelta por `GET /api/v1/reports/dashboard`. */
  readonly salesByHour = input<readonly DashboardHourlySales[]>([]);
  /** Fecha de negocio consultada (`YYYY-MM-DD`), usada como contexto descriptivo. */
  readonly businessDate = input<string | undefined>(undefined);

  protected readonly viewMode = signal<'chart' | 'table'>('chart');
  protected readonly selectedHour = signal<number | null>(null);
  protected readonly tableColumns: readonly string[] = ['hour', 'transactions', 'total'];

  /** Serie densa de exactamente 24 horas ordenadas (`0..23`). */
  protected readonly points = computed<readonly NormalizedHourlySales[]>(() =>
    normalizeHourlySeries(this.salesByHour()),
  );

  /** Monto máximo horario en CLP usado para escalar las barras del gráfico. */
  protected readonly maxTotal = computed(() =>
    Math.max(0, ...this.points().map((point) => point.total)),
  );

  /** Punto medio del eje vertical para referencia visual. */
  protected readonly halfMaxTotal = computed(() => Math.round(this.maxTotal() / 2));

  /** Indica si existe al menos una venta o transacción registrada en el día. */
  protected readonly hasSales = computed(() =>
    this.points().some((point) => point.total > 0 || point.transactions > 0),
  );

  /** Hora de mayor venta neta del día (o `null` si no hubo ventas). */
  protected readonly peakHour = computed(() => findPeakHour(this.points()));

  /** Hora actualmente seleccionada o enfocada; si no hay selección manual, resalta la hora punta. */
  protected readonly activePoint = computed<NormalizedHourlySales | null>(() => {
    const selected = this.selectedHour();
    if (selected !== null) {
      return this.points().find((point) => point.hour === selected) ?? null;
    }
    return this.peakHour();
  });

  protected readonly formattedDate = computed(() => formatLocalDate(this.businessDate()));

  /** Alterna entre la vista de gráfico de barras y la tabla horaria completa. */
  protected toggleViewMode(): void {
    this.viewMode.update((mode) => (mode === 'chart' ? 'table' : 'chart'));
  }

  /** Selecciona una hora para mostrar su detalle en el pie del gráfico. */
  protected selectHour(hour: number): void {
    this.selectedHour.set(hour);
  }

  /** Calcula el porcentaje de altura (`0..100`) de la barra respecto a la hora punta. */
  protected barHeightPct(point: NormalizedHourlySales): number {
    const max = this.maxTotal();
    if (max <= 0 || point.total <= 0) {
      return 0;
    }
    return Math.max(MIN_NON_ZERO_BAR_PCT, Math.round((point.total / max) * 100));
  }

  /** Construye el texto accesible y de tooltip para cada barra horaria. */
  protected barAriaLabel(point: NormalizedHourlySales): string {
    return `${point.label}: ${this.formatClpInline(point.total)}, ${this.formatTransactions(point.transactions)}`;
  }

  protected formatTransactions(count: number): string {
    const formatted = formatInteger(count);
    return count === 1 ? `${formatted} venta` : `${formatted} ventas`;
  }

  protected formatInt(value: number): string {
    return formatInteger(value);
  }

  private formatClpInline(amount: number): string {
    return `$${formatInteger(amount)}`;
  }
}
